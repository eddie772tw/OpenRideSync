package org.openridesync.diagnostics;

import java.io.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Bounded metadata only. No message, URL, exception text, token or arbitrary field API. */
public final class DiagnosticJournal implements AutoCloseable {
    public enum Component { APP, UI, AUTH, API, SYNC, BLE, MEDIA, NOTIFICATION, NAVIGATION, TRIP, SDK, DIAGNOSTICS }
    public enum Event { START, STOP, STATE_CHANGE, REQUEST, RESPONSE, RETRY, CONNECTED, DISCONNECTED, EXPORT, ARCHIVE, UNAVAILABLE, CHECKPOINT, HONDA_GET_USER, HONDA_GET_COUNTRY, HONDA_LIST_MODEL_CATALOGS, HONDA_LIST_MODEL_SERIES, HONDA_LIST_MODELS, HONDA_GET_IMAGE, HONDA_GET_SETUP_PAGE, HONDA_GET_TUTORIAL_PAGE, GUIDE_DOWNLOAD, HONDA_LIST_DAILY_TRIPS, HONDA_LIST_TRIP_DETAILS, HONDA_GET_TRIP_DETAIL, HONDA_GET_WEATHER, HONDA_GET_WEATHER_FORECAST, TOKEN_READ, TOKEN_REFRESH, BLE_SCAN, BLE_BOND, BLE_GATT_CONNECT, BLE_DISCOVER_SERVICES, BLE_CCCD_WRITE, BLE_SECURITY_STATUS, BLE_SECURITY_RSA_EXCHANGE, BLE_SECURITY_AES_VERIFY, PAIRING_ASSOCIATION, BLE_BTU_INFORMATION, PAIRED_VEHICLE_SAVE, BLE_CTS_START, BLE_CTS_REQUEST, BLE_CTS_NOTIFY, BLE_ASSIGNMENT_REGISTER, BLE_CONTROL_COMMAND, BLE_CONTROL_RECEIPT, BLE_RUNTIME_INIT, BLE_FUNCTION_NOTIFICATION, BLE_DRIVING_NOTIFICATION, BLE_RUNTIME_SERVICE, BLE_RUNTIME_STATE, BLE_RUNTIME_STOP, MEDIA_ACCESS, MEDIA_BROWSER, MEDIA_SESSION, MEDIA_PLAY, MEDIA_PAUSE, MEDIA_PREVIOUS, MEDIA_NEXT }
    public enum Outcome { STARTED, SUCCEEDED, FAILED, CANCELLED, UNAVAILABLE, OBSERVED }
    public enum ErrorCode { NONE, IO, TIMEOUT, CANCELLED, UNAUTHORIZED, NETWORK, INVALID_STATE, UNSUPPORTED, UNKNOWN, INVALID_ARGUMENT, NOT_FOUND, ALREADY_EXISTS, PERMISSION_DENIED, RESOURCE_EXHAUSTED, FAILED_PRECONDITION, ABORTED, OUT_OF_RANGE, UNIMPLEMENTED, INTERNAL, DATA_LOSS, UNAUTHENTICATED, UNAVAILABLE, DEADLINE_EXCEEDED }
    public static final String FILE_NAME = "openridesync-diagnostics-v1.tsv";
    public static final String HEADER = "schema\tsession\tepoch_ms\telapsed_ms\tsequence\tcomponent\toperation\tevent\toutcome\terror\taccepted\twritten\tdropped\twrite_failures\tstatus";
    private static final int RESERVE = 2048;
    private final File file;
    private final DiagnosticArchives storage;
    private final long maxBytes;
    private long started = System.nanoTime();
    private final int capacity;
    private String session = UUID.randomUUID().toString();
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final Object disk = new Object();
    private long sequence, accepted, written, dropped, failures, requested, checkpoint;
    private boolean closed, sizeLimited, checkpointOk;
    private FutureTask<Archive> archiveTask;
    private final Thread worker;

    public DiagnosticJournal(File directory) { this(directory, 128, 8L * 1024 * 1024); }
    public DiagnosticJournal(File directory, int capacity, long maxBytes) {
        if (directory == null || capacity < 1 || maxBytes < 4096) throw new IllegalArgumentException("journal limits");
        this.file = new File(directory, FILE_NAME); this.capacity = capacity; this.maxBytes = maxBytes;
        storage = new DiagnosticArchives(file);
        worker = new Thread(this::run, "openridesync-diagnostics");
        worker.setDaemon(true); worker.start();
    }
    public String newOperation() { return UUID.randomUUID().toString(); }
    public synchronized boolean record(Component component, String operation, Event event, Outcome outcome, ErrorCode error) {
        // UUID-only correlations deliberately cannot carry notification/auth payloads.
        if (component == null || event == null || event == Event.CHECKPOINT || outcome == null || error == null ||
                (operation != null && !operation.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
            dropped++; requestIntegrityCheckpoint(); return false;
        }
        if (closed || sizeLimited || queue.size() >= capacity) { dropped++; requestIntegrityCheckpoint(); return false; }
        accepted++;
        queue.add(prefix(++sequence) + component + "\t" + (operation == null ? "-" : operation) + "\t" + event + "\t" + outcome + "\t" + error);
        notifyAll(); return true;
    }
    private void requestIntegrityCheckpoint() {
        if (!closed) { requested++; notifyAll(); }
    }
    private String prefix(long seq) {
        return "1\t" + session + "\t" + System.currentTimeMillis() + "\t" + ((System.nanoTime() - started) / 1_000_000) + "\t" + seq + "\t";
    }
    private synchronized String suffix() {
        return "\t" + accepted + "\t" + written + "\t" + dropped + "\t" + failures + "\t" +
            (sizeLimited ? "SIZE_LIMIT" : failures > 0 ? "IO_ERROR" : dropped > 0 ? "DROPPED" : "OK") + "\n";
    }
    private void run() {
        while (true) {
            String row; long target = 0; FutureTask<Archive> archive = null;
            synchronized (this) {
                while (queue.isEmpty() && requested == checkpoint && archiveTask == null && !closed) {
                    try { wait(); } catch (InterruptedException ignored) { /* owner closes via flag */ }
                }
                if (!queue.isEmpty()) row = queue.removeFirst();
                else if (archiveTask != null) { archive = archiveTask; row = null; }
                else {
                    if (requested == checkpoint && closed) return;
                    target = requested;
                    row = prefix(++sequence) + "DIAGNOSTICS\t-\tCHECKPOINT\tOBSERVED\tNONE";
                }
            }
            if (archive != null) {
                archive.run();
                continue;
            }
            boolean ok = append(row + suffix(), target != 0);
            synchronized (this) {
                if (target != 0) { checkpoint = target; checkpointOk = ok; }
                else if (ok) written++;
                else if (queue.isEmpty()) requested++;
                notifyAll();
            }
        }
    }
    private boolean append(String row, boolean terminal) {
        synchronized (disk) {
            try {
                File parent = file.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("directory");
                try (DiagnosticArchives.Lock stable = storage.lock(false);
                        RandomAccessFile out = new RandomAccessFile(file, "rw"); FileLock lock = out.getChannel().tryLock()) {
                    if (lock == null) throw new IOException("busy");
                    byte[] header = (HEADER + "\n").getBytes(StandardCharsets.UTF_8);
                    long length = out.length();
                    if (length == 0) { out.write(header); length = header.length; }
                    else {
                        byte[] actual = new byte[header.length]; out.readFully(actual);
                        if (!java.util.Arrays.equals(header, actual)) throw new IOException("schema");
                        out.seek(length - 1);
                        if (out.read() != '\n') throw new IOException("partial row");
                    }
                    byte[] bytes = row.getBytes(StandardCharsets.UTF_8);
                    if (length + bytes.length > maxBytes - (terminal ? 0 : RESERVE)) {
                        synchronized (this) { sizeLimited = true; if (!terminal) dropped++; }
                        return false;
                    }
                    out.seek(length); out.write(bytes); out.getFD().sync(); return true;
                }
            } catch (IOException | RuntimeException e) {
                synchronized (this) { failures++; }
                return false;
            }
        }
    }
    /** Waits for a persisted checkpoint after all queued records. Preserves caller interruption. */
    public synchronized boolean flush(long timeoutMillis) {
        if (timeoutMillis < 0) throw new IllegalArgumentException("timeout");
        if (!worker.isAlive()) return checkpointOk;
        long target = ++requested; notifyAll();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (checkpoint < target) {
            long left = deadline - System.nanoTime(); if (left <= 0) return false;
            try { java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, left); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return checkpointOk;
    }
    public File snapshot(File destination, long timeoutMillis) throws IOException {
        storage.checkDestination(destination);
        if (!flush(timeoutMillis)) throw new IOException("journal checkpoint unavailable");
        synchronized (disk) {
            storage.checkDestination(destination);
            // Copy under the same cross-process lock used by append; never export a partial row.
            try (DiagnosticArchives.Lock stable = storage.lock(true);
                    RandomAccessFile source = new RandomAccessFile(file, "r"); FileLock lock = source.getChannel().tryLock(0, Long.MAX_VALUE, true)) {
                if (lock == null) throw new IOException("journal busy");
                Files.copy(file.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (RuntimeException e) { throw new IOException("journal busy", e); }
        }
        return destination;
    }
    void checkExportDestination(File destination) throws IOException {
        storage.checkDestination(destination);
    }
    public static final class Archive {
        public final String id, sha256;
        public final long bytes, createdAtMillis, rows, sessions;
        public final boolean complete;
        Archive(String id, long bytes, long created, long rows, long sessions, boolean complete, String hash) {
            this.id=id; this.bytes=bytes; createdAtMillis=created; this.rows=rows; this.sessions=sessions;
            this.complete=complete; sha256=hash;
        }
    }
    /** Explicit off-main operation, available only at the cap. Never rotates automatically.
     * Caller interruption is restored after the worker finishes, avoiding an unknown commit. */
    public Archive archiveAndResume() throws IOException {
        FutureTask<Archive> task;
        synchronized (this) {
            if (closed || !sizeLimited || archiveTask != null) throw new IOException("archive unavailable");
            task = new FutureTask<>(() -> {
                try { return rotateOnWorker(); }
                finally {
                    // Release ownership before FutureTask publishes completion to get() callers.
                    // A caller may immediately retry after an I/O failure or a released lock.
                    synchronized (this) { archiveTask = null; notifyAll(); }
                }
            });
            archiveTask = task; notifyAll();
        }
        boolean interrupted = false;
        try {
            while (true) try { return task.get(); }
            catch (InterruptedException e) { interrupted = true; }
            catch (ExecutionException e) { throw new IOException("archive failed", e.getCause()); }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private Archive rotateOnWorker() throws IOException {
        long target; String row;
        synchronized (this) {
            if (closed || !sizeLimited) throw new IOException("archive unavailable");
            target = requested;
            row = prefix(++sequence) + "DIAGNOSTICS\t-\tCHECKPOINT\tOBSERVED\tNONE";
        }
        // Full integrity reserve may already be exhausted. Preserve the bytes that do exist.
        boolean ok = append(row + suffix(), true);
        synchronized (this) { checkpoint = target; checkpointOk = ok; notifyAll(); }
        synchronized (disk) {
            try (DiagnosticArchives.Lock stable = storage.lock(false)) {
                String nextSession = UUID.randomUUID().toString();
                byte[] fresh = (HEADER + "\n1\t" + nextSession + "\t" + System.currentTimeMillis() +
                    "\t0\t1\tDIAGNOSTICS\t-\tCHECKPOINT\tOBSERVED\tNONE\t0\t0\t0\t0\tOK\n").getBytes(StandardCharsets.UTF_8);
                Archive archive = storage.rotate(fresh);
                synchronized (this) {
                    session = nextSession; started = System.nanoTime(); sequence = 1;
                    accepted = 0; written = 0; dropped = 0; failures = 0; sizeLimited = false;
                    // requested/checkpoint belong to flush waiters, not to the new session.
                }
                return archive;
            } catch (RuntimeException e) { throw new IOException("journal busy", e); }
        }
    }
    public List<Archive> archives() throws IOException {
        synchronized (disk) {
            try (DiagnosticArchives.Lock stable = storage.lock(true)) { return storage.list(); }
        }
    }
    public File snapshotArchive(String id, File destination) throws IOException {
        DiagnosticArchives.id(id);
        synchronized (disk) {
            try (DiagnosticArchives.Lock stable = storage.lock(true)) { return storage.snapshot(id, destination); }
        }
    }
    public synchronized Status status() { return new Status(accepted, written, dropped, failures, sizeLimited, closed); }
    public static final class Status {
        public final long accepted, written, dropped, writeFailures;
        public final boolean sizeLimited, closed;
        private Status(long a, long w, long d, long f, boolean l, boolean c) {
            accepted=a; written=w; dropped=d; writeFailures=f; sizeLimited=l; closed=c;
        }
    }
    @Override public void close() {
        synchronized (this) { if (!closed) { closed = true; requested++; notifyAll(); } }
        try { worker.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

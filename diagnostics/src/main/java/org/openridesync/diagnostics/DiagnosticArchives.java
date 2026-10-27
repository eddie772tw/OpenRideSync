package org.openridesync.diagnostics;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Journal-private immutable evidence. A metadata file is admitted only after full verification. */
final class DiagnosticArchives {
    final File live, sidecar, directory;
    DiagnosticArchives(File live) {
        this.live = live;
        sidecar = new File(live.getParentFile(), DiagnosticJournal.FILE_NAME + ".lock");
        directory = new File(live.getParentFile(), "diagnostic-archives");
    }

    static final class Lock implements AutoCloseable {
        private final RandomAccessFile file;
        private final FileLock lock;
        Lock(File path, boolean shared) throws IOException {
            file = new RandomAccessFile(path, "rw");
            try {
                lock = file.getChannel().tryLock(0, Long.MAX_VALUE, shared);
                if (lock == null) throw new IOException("journal busy");
            } catch (IOException | RuntimeException e) {
                file.close();
                throw new IOException("journal busy", e);
            }
        }
        @Override public void close() throws IOException { try { lock.close(); } finally { file.close(); } }
    }

    Lock lock(boolean shared) throws IOException {
        File parent = live.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("journal directory");
        return new Lock(sidecar, shared);
    }

    static String id(String value) throws IOException {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException e) { throw new IOException("invalid archive ID", e); }
    }
    private File data(String id) throws IOException { return new File(directory, id(id) + ".tsv"); }
    private File metadata(String id) throws IOException { return new File(directory, id(id) + ".properties"); }

    void checkDestination(File destination) throws IOException {
        if (destination == null) throw new IOException("destination required");
        File canonical = destination.getCanonicalFile();
        File archiveRoot = directory.getCanonicalFile();
        if (canonical.toPath().startsWith(archiveRoot.toPath())) throw new IOException("destination aliases archive storage");
        File parent = live.getParentFile().getCanonicalFile();
        if (parent.equals(canonical.getParentFile()) && canonical.getName().startsWith(DiagnosticJournal.FILE_NAME))
            throw new IOException("destination aliases journal storage");
        if (Files.exists(destination.toPath())) {
            if (same(destination, live) || same(destination, sidecar)) throw new IOException("destination aliases journal");
            // Canonical paths detect symlinks; isSameFile also protects hard-linked evidence.
            File[] children = directory.listFiles();
            if (children != null) for (File child : children)
                if (same(destination, child)) throw new IOException("destination aliases archive");
        }
    }
    private static boolean same(File a, File b) throws IOException {
        return Files.exists(b.toPath()) && Files.isSameFile(a.toPath(), b.toPath());
    }

    DiagnosticJournal.Archive rotate(byte[] fresh) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("archive directory");
        String id = UUID.randomUUID().toString();
        File archived = data(id);
        File meta = metadata(id);
        File pendingMetadata = new File(directory, id + ".properties.pending");
        File staging = new File(live.getParentFile(), DiagnosticJournal.FILE_NAME + ".resume-" + id + ".tmp");
        try {
            if (meta.exists()) throw new IOException("archive already exists");
            DiagnosticReader.Result result;
            String hash;
            // The actual-file lock protects the evidence copy, including compatibility with
            // existing lock holders. Read through its owning handle: Windows locks are mandatory.
            try (FileChannel source = FileChannel.open(live.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE);
                    FileLock lock = source.tryLock()) {
                if (lock == null) throw new IOException("journal busy");
                String sourceHash = copyLocked(source, archived);
                result = DiagnosticReader.read(archived);
                hash = hash(archived);
                if (archived.length() != source.size() || !hash.equals(sourceHash)) throw new IOException("archive copy mismatch");
            }
            // Close the live handle before Windows replacement. The caller retains the stable
            // sidecar lock through atomic replacement AND its session-counter reset; every
            // current append/snapshot honors that lock even when the live file identity changes.
            DiagnosticJournal.Archive archive = new DiagnosticJournal.Archive(id, archived.length(),
                System.currentTimeMillis(), result.rows, result.sessions, result.complete, hash);
            Properties values = new Properties();
            values.setProperty("schema", "1"); values.setProperty("id", id);
            values.setProperty("bytes", Long.toString(archive.bytes));
            values.setProperty("created_at_ms", Long.toString(archive.createdAtMillis));
            values.setProperty("rows", Long.toString(archive.rows));
            values.setProperty("sessions", Long.toString(archive.sessions));
            values.setProperty("complete", Boolean.toString(archive.complete));
            values.setProperty("sha256", hash);
            Files.createFile(pendingMetadata.toPath());
            try (FileOutputStream out = new FileOutputStream(pendingMetadata)) { values.store(out, null); out.getFD().sync(); }
            Files.move(pendingMetadata.toPath(), meta.toPath(), StandardCopyOption.ATOMIC_MOVE);
            verify(id); // Metadata and raw bytes must agree before the live file can change.
            Files.createFile(staging.toPath());
            try (FileOutputStream out = new FileOutputStream(staging)) { out.write(fresh); out.getFD().sync(); }
            if (!DiagnosticReader.read(staging).complete) throw new IOException("fresh session incomplete");
            Files.move(staging.toPath(), live.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return archive;
        } finally { Files.deleteIfExists(staging.toPath()); }
    }

    private static String copyLocked(FileChannel source, File destination) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            source.position(0);
            try (FileChannel out = FileChannel.open(destination.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                while (source.read(buffer) != -1) {
                    digest.update(buffer.array(), 0, buffer.position());
                    buffer.flip();
                    while (buffer.hasRemaining()) out.write(buffer);
                    buffer.clear();
                }
                out.force(true);
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable", e); }
    }

    List<DiagnosticJournal.Archive> list() throws IOException {
        if (!directory.exists()) return Collections.emptyList();
        File[] entries = directory.listFiles();
        if (entries == null) throw new IOException("archive directory unavailable");
        List<DiagnosticJournal.Archive> result = new ArrayList<>();
        for (File entry : entries) if (entry.getName().endsWith(".properties"))
            result.add(verify(entry.getName().substring(0, entry.getName().length() - 11)));
        result.sort(Comparator.comparingLong((DiagnosticJournal.Archive value) -> value.createdAtMillis).thenComparing(value -> value.id));
        return Collections.unmodifiableList(result);
    }

    DiagnosticJournal.Archive verify(String id) throws IOException {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(metadata(id))) { p.load(in); }
        try {
            if (p.size() != 8 || !"1".equals(p.getProperty("schema")) || !id.equals(p.getProperty("id"))) throw new IllegalArgumentException();
            long bytes = Long.parseLong(p.getProperty("bytes")), created = Long.parseLong(p.getProperty("created_at_ms"));
            long rows = Long.parseLong(p.getProperty("rows")), sessions = Long.parseLong(p.getProperty("sessions"));
            String complete = p.getProperty("complete"), expected = p.getProperty("sha256");
            if (bytes < 1 || created < 1 || rows < 1 || sessions < 1 || !("true".equals(complete) || "false".equals(complete)) ||
                    expected == null || !expected.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
            File file = data(id);
            DiagnosticReader.Result parsed = DiagnosticReader.read(file);
            if (file.length() != bytes || parsed.rows != rows || parsed.sessions != sessions ||
                    parsed.complete != Boolean.parseBoolean(complete) || !expected.equals(hash(file))) throw new IOException("archive verification failed");
            return new DiagnosticJournal.Archive(id, bytes, created, rows, sessions, parsed.complete, expected);
        } catch (IllegalArgumentException e) { throw new IOException("invalid archive metadata", e); }
    }

    File snapshot(String id, File destination) throws IOException {
        checkDestination(destination);
        DiagnosticJournal.Archive archive = verify(id);
        File source = data(archive.id);
        try (RandomAccessFile in = new RandomAccessFile(source, "r"); FileLock lock = in.getChannel().tryLock(0, Long.MAX_VALUE, true)) {
            if (lock == null) throw new IOException("archive busy");
            Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (destination.length() != archive.bytes || !archive.sha256.equals(hash(destination)))
                throw new IOException("archive snapshot mismatch");
        } catch (RuntimeException e) { throw new IOException("archive busy", e); }
        return destination;
    }

    static String hash(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable", e); }
    }
    private static String hex(byte[] digest) {
        StringBuilder value = new StringBuilder();
        for (byte b : digest) value.append(String.format(Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
}

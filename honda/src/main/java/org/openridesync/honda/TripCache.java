package org.openridesync.honda;

import com.google.protobuf.MessageLite;
import org.openridesync.honda.wire.HondaWire.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

/** Raw server snapshots. The caller owns UID isolation and serializes file writes. */
public final class TripCache {
    private static final int MAGIC = 0x4f525443, VERSION = 1;
    private static final int LIMIT = 8 * 1024 * 1024;
    private static final long TOTAL_LIMIT = 64L * 1024 * 1024;
    private final File directory;
    public TripCache(File directory) { this.directory = java.util.Objects.requireNonNull(directory); }

    public record Snapshot<T>(T value, long savedAtMillis) { }
    private record Entry(byte[] bytes, long savedAtMillis) { }

    public Snapshot<ListDailyTripsResponse> readDaily(ListDailyTripsRequest request) throws IOException {
        Entry entry = read("daily", request);
        return entry == null ? null : new Snapshot<>(ListDailyTripsResponse.parseFrom(entry.bytes), entry.savedAtMillis);
    }
    public void writeDaily(ListDailyTripsRequest request, ListDailyTripsResponse response) throws IOException {
        write("daily", request, response);
    }
    /** Index the first page's exact request so an app restart can find it without reusing today's clock value. */
    public ListDailyTripsRequest latestDailyRequest(ListDailyTripsRequest scope) throws IOException {
        ListDailyTripsRequest key = scope.toBuilder().clearStartDate().build();
        Entry entry = read("head", key);
        if (entry == null) return null;
        ListDailyTripsRequest request = ListDailyTripsRequest.parseFrom(entry.bytes);
        if (!request.toBuilder().clearStartDate().build().equals(key)) throw new IOException("trip cache scope mismatch");
        return request;
    }
    public void indexLatestDaily(ListDailyTripsRequest request) throws IOException {
        write("head", request.toBuilder().clearStartDate().build(), request);
    }
    public Snapshot<ListTripDetailsResponse> readDetails(ListTripDetailsRequest request) throws IOException {
        Entry entry = read("details", request);
        return entry == null ? null : new Snapshot<>(ListTripDetailsResponse.parseFrom(entry.bytes), entry.savedAtMillis);
    }
    public void writeDetails(ListTripDetailsRequest request, ListTripDetailsResponse response) throws IOException {
        write("details", request, response);
    }

    private File file(String kind, MessageLite request) throws IOException {
        StringBuilder name = new StringBuilder(kind).append('-');
        for (byte b : hash(request.toByteArray())) name.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        File file = new File(directory, name + ".bin");
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("symlink trip cache");
        return file;
    }
    private Entry read(String kind, MessageLite request) throws IOException {
        File file = file(kind, request);
        if (!file.exists()) return null;
        if (!file.isFile() || file.length() > LIMIT || file.length() < 52) throw new IOException("invalid trip cache size");
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("unsupported trip cache");
            long saved = in.readLong();
            int length = in.readInt();
            if (saved <= 0 || length < 0 || file.length() != 52L + length) throw new IOException("invalid trip cache length");
            byte[] digest = new byte[32], bytes = new byte[length];
            in.readFully(digest); in.readFully(bytes);
            // Include the complete request and type in the digest; renaming a valid file cannot change its identity.
            if (!MessageDigest.isEqual(digest, digest(kind, request, saved, bytes))) throw new IOException("trip cache digest mismatch");
            return new Entry(bytes, saved);
        }
    }
    private void write(String kind, MessageLite request, MessageLite response) throws IOException {
        if (response.getSerializedSize() > LIMIT - 52) throw new IOException("trip cache size limit");
        byte[] bytes = response.toByteArray();
        long saved = System.currentTimeMillis();
        byte[] digest = digest(kind, request, saved, bytes);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("trip cache unavailable");
        File target = file(kind, request), temporary = File.createTempFile("trip-", ".tmp", directory);
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary); DataOutputStream out = new DataOutputStream(stream)) {
                out.writeInt(MAGIC); out.writeInt(VERSION); out.writeLong(saved); out.writeInt(bytes.length);
                out.write(digest); out.write(bytes); out.flush(); stream.getFD().sync();
            }
            trim(target, temporary.length());
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
    private void trim(File keep, long incomingBytes) throws IOException {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".bin"));
        if (files == null) throw new IOException("trip cache listing failed");
        long total = incomingBytes;
        for (File file : files) if (!file.equals(keep)) total += file.length();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            if (total <= TOTAL_LIMIT) break;
            if (!file.equals(keep)) { long size = file.length(); Files.delete(file.toPath()); total -= size; }
        }
    }
    private static byte[] digest(String kind, MessageLite request, long saved, byte[] bytes) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(payload)) {
            byte[] key = request.toByteArray();
            out.writeUTF(kind); out.writeInt(key.length); out.write(key); out.writeLong(saved); out.write(bytes);
        }
        return hash(payload.toByteArray());
    }
    private static byte[] hash(byte[] bytes) throws IOException {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 unavailable", impossible); }
    }
}

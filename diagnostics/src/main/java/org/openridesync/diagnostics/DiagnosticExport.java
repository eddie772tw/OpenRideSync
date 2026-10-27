package org.openridesync.diagnostics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class DiagnosticExport {
    private DiagnosticExport() {}
    /** Call off the UI thread. Exports only this metadata journal, never arbitrary private files. */
    public static File export(DiagnosticJournal journal, File destination, String packageName,
            long versionCode, String commit, int sdk) throws IOException {
        return export(journal, null, destination, packageName, versionCode, commit, sdk);
    }
    /** Export a verified, immutable archive by its journal-issued ID, with the same v1 format. */
    public static File exportArchive(DiagnosticJournal journal, String archiveId, File destination,
            String packageName, long versionCode, String commit, int sdk) throws IOException {
        if (archiveId == null) throw new IOException("archive ID required");
        return export(journal, archiveId, destination, packageName, versionCode, commit, sdk);
    }
    private static File export(DiagnosticJournal journal, String archiveId, File destination,
            String packageName, long versionCode, String commit, int sdk) throws IOException {
        journal.checkExportDestination(destination);
        if (!"org.openridesync.app".equals(packageName) || versionCode < 0 || sdk < 30 || sdk > 999 ||
                commit == null || !commit.matches("[0-9a-f]{7,40}|unknown")) throw new IOException("invalid export identity");
        File parent = destination.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("export directory unavailable");
        File snapshot = File.createTempFile("diagnostics-", ".tsv", parent);
        File zip = File.createTempFile("diagnostics-", ".zip", parent);
        try {
            if (archiveId == null) journal.snapshot(snapshot, 5000);
            else journal.snapshotArchive(archiveId, snapshot);
            DiagnosticReader.Result validated = DiagnosticReader.read(snapshot);
            byte[] data = Files.readAllBytes(snapshot.toPath());
            String hash;
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
                StringBuilder hex = new StringBuilder();
                for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                hash = hex.toString();
            } catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 unavailable", impossible); }
            String manifest = "schema=1\npackage=" + packageName + "\nversion_code=" + versionCode +
                "\ncommit=" + commit + "\nandroid_sdk=" + sdk + "\ncapability_scope=metadata_journal_only\n" +
                "evidence=app_metadata_not_vehicle_validation\nfile=" + DiagnosticJournal.FILE_NAME +
                "\nsha256=" + hash + "\nbytes=" + data.length + "\nrows=" + validated.rows +
                "\nsessions=" + validated.sessions + "\ncomplete=" + validated.complete + "\n";
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
                out.putNextEntry(new ZipEntry(DiagnosticJournal.FILE_NAME)); out.write(data); out.closeEntry();
                out.putNextEntry(new ZipEntry("manifest.properties")); out.write(manifest.getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            }
            Files.move(zip.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return destination;
        } finally { Files.deleteIfExists(snapshot.toPath()); Files.deleteIfExists(zip.toPath()); }
    }
}

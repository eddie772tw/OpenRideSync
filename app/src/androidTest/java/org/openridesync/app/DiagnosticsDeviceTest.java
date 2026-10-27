package org.openridesync.app;

import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openridesync.diagnostics.DiagnosticExport;
import org.openridesync.diagnostics.DiagnosticJournal;
import org.openridesync.diagnostics.DiagnosticReader;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipFile;
import static org.junit.Assert.*;
import static org.openridesync.diagnostics.DiagnosticJournal.*;

/** Device runtime/filesystem coverage only. Performs no account, radio or notification action. */
@RunWith(AndroidJUnit4.class)
public final class DiagnosticsDeviceTest {
    private File ownDirectory;
    private DiagnosticJournal journal;

    @Before public void prepare() throws Exception {
        File cache = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        ownDirectory = Files.createTempDirectory(cache.toPath(), "diagnostics-instrumentation-").toFile();
    }

    @After public void cleanup() throws Exception {
        if (journal != null) journal.close();
        if (ownDirectory != null) removeOwned(ownDirectory);
    }

    private void removeOwned(File target) throws Exception {
        String base = ownDirectory.getCanonicalPath();
        String path = target.getCanonicalPath();
        assertTrue("cleanup must remain inside this test directory", path.equals(base) || path.startsWith(base + File.separator));
        File[] children = target.listFiles();
        if (children != null) for (File child : children) removeOwned(child);
        assertTrue("remove generated test artifact", target.delete() || !target.exists());
    }

    private static byte[] journalBytes(ZipFile archive) throws Exception {
        try (InputStream source = archive.getInputStream(archive.getEntry(DiagnosticJournal.FILE_NAME))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = source.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hash.toString();
    }

    @Test public void applicationInitializesJournalBeforeScreen() {
        Object application = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        assertTrue("manifest Application is active", application instanceof OpenRideSyncApplication);
        DiagnosticJournal startup = ((OpenRideSyncApplication) application).getJournal();
        assertNotNull("native diagnostics initialized in Application", startup);
        assertTrue("Application submitted its startup metadata", startup.status().accepted >= 1);
        assertFalse("Application journal remains open", startup.status().closed);
        // The real startup journal is observed only; isolated files below ownDirectory own all writes.
    }

    @Test public void androidRuntimePersistsReadsAndExportsValidatedMetadata() throws Exception {
        journal = new DiagnosticJournal(new File(ownDirectory, "journal"));
        String operation = journal.newOperation();
        assertTrue(journal.record(Component.UI, operation, Event.START, Outcome.OBSERVED, ErrorCode.NONE));
        assertTrue("writer persisted a checkpoint", journal.flush(5000));
        assertEquals(1, journal.status().written);
        assertEquals(0, journal.status().writeFailures);
        assertEquals(0, journal.status().dropped);

        File snapshot = journal.snapshot(new File(ownDirectory, "snapshot.tsv"), 5000);
        DiagnosticReader.Result read = DiagnosticReader.read(snapshot);
        assertTrue("snapshot is complete metadata evidence", read.complete);
        assertEquals(1, read.sessions);
        assertTrue(read.rows >= 2);
        assertTrue(new String(Files.readAllBytes(snapshot.toPath()), StandardCharsets.UTF_8).contains("\tUI\t" + operation + "\tSTART\tOBSERVED\tNONE\t"));

        File export = DiagnosticExport.export(journal, new File(ownDirectory, "diagnostics.zip"),
            "org.openridesync.app", 1, "unknown", Build.VERSION.SDK_INT);
        try (ZipFile archive = new ZipFile(export)) {
            assertEquals("fixed export members only", 2, archive.size());
            Properties manifest = new Properties();
            try (InputStream source = archive.getInputStream(archive.getEntry("manifest.properties"))) {
                manifest.load(source);
            }
            byte[] tsv = journalBytes(archive);
            assertEquals(sha256(tsv), manifest.getProperty("sha256"));
            assertEquals(Integer.toString(tsv.length), manifest.getProperty("bytes"));
            assertEquals("org.openridesync.app", manifest.getProperty("package"));
            assertEquals(Integer.toString(Build.VERSION.SDK_INT), manifest.getProperty("android_sdk"));
            assertEquals("true", manifest.getProperty("complete"));
            assertEquals("app_metadata_not_vehicle_validation", manifest.getProperty("evidence"));
            File exportedTsv = new File(ownDirectory, "exported.tsv");
            Files.write(exportedTsv.toPath(), tsv);
            DiagnosticReader.Result exported = DiagnosticReader.read(exportedTsv);
            assertTrue(exported.complete);
            assertEquals(Long.toString(exported.rows), manifest.getProperty("rows"));
            assertEquals(Long.toString(exported.sessions), manifest.getProperty("sessions"));
        }
    }

    @Test public void androidRuntimeArchivesExhaustedJournalResumesAndExportsAfterReopen() throws Exception {
        File directory = new File(ownDirectory, "journal");
        File live = new File(directory, DiagnosticJournal.FILE_NAME);
        journal = new DiagnosticJournal(directory, 128, 4096);
        synchronized (journal) {
            for (int i = 0; i < 30; i++) assertTrue(journal.record(Component.BLE, null, Event.REQUEST, Outcome.OBSERVED, ErrorCode.NONE));
        }
        assertTrue("initial cap checkpoint fits its reserve", journal.flush(5000));
        assertTrue("small isolated journal reaches its cap", journal.status().sizeLimited);
        boolean reserveExhausted = false;
        for (int i = 0; i < 40; i++) {
            if (!journal.flush(5000)) { reserveExhausted = true; break; }
        }
        assertTrue("bounded checkpoints exhaust the integrity reserve", reserveExhausted);
        byte[] original = Files.readAllBytes(live.toPath());
        String originalHash = sha256(original);

        Archive archived = journal.archiveAndResume();
        assertFalse("old missing records remain incomplete", archived.complete);
        assertEquals(original.length, archived.bytes);
        assertEquals(originalHash, archived.sha256);
        assertFalse("same journal instance can accept records again", journal.status().sizeLimited);
        assertTrue(journal.record(Component.APP, null, Event.START, Outcome.OBSERVED, ErrorCode.NONE));
        File fresh = journal.snapshot(new File(ownDirectory, "resumed.tsv"), 5000);
        assertTrue("new session is independently complete", DiagnosticReader.read(fresh).complete);
        assertEquals(1, journal.status().written);
        File old = journal.snapshotArchive(archived.id, new File(ownDirectory, "archived.tsv"));
        assertFalse(DiagnosticReader.read(old).complete);
        assertArrayEquals("resumed writes leave old raw bytes untouched", original, Files.readAllBytes(old.toPath()));

        journal.close();
        journal = new DiagnosticJournal(directory, 128, 4096);
        assertTrue(journal.record(Component.APP, null, Event.START, Outcome.OBSERVED, ErrorCode.NONE));
        DiagnosticReader.Result restarted = DiagnosticReader.read(journal.snapshot(fresh, 5000));
        assertTrue("reopening preserves the resumed session", restarted.complete);
        assertEquals(2, restarted.sessions);
        assertEquals(1, journal.archives().size());
        assertEquals(archived.id, journal.archives().get(0).id);
        File export = DiagnosticExport.exportArchive(journal, archived.id, new File(ownDirectory, "archived.zip"),
            "org.openridesync.app", 1, "unknown", Build.VERSION.SDK_INT);
        try (ZipFile zip = new ZipFile(export)) {
            assertEquals("same fixed export members", 2, zip.size());
            byte[] exported = journalBytes(zip);
            assertArrayEquals("reopened archive export preserves exact evidence", original, exported);
            Properties manifest = new Properties();
            try (InputStream source = zip.getInputStream(zip.getEntry("manifest.properties"))) { manifest.load(source); }
            assertEquals(originalHash, sha256(exported));
            assertEquals(originalHash, manifest.getProperty("sha256"));
            assertEquals("false", manifest.getProperty("complete"));
        }
        journal.snapshotArchive(archived.id, old);
        assertArrayEquals("archive remains unchanged after restart and export", original, Files.readAllBytes(old.toPath()));
    }

    @Test public void androidRuntimeRejectsUntrustedMetadataWithoutPersistingIt() throws Exception {
        journal = new DiagnosticJournal(new File(ownDirectory, "journal"));
        String untrusted = "synthetic-content\tunexpected-field\n";
        assertFalse(journal.record(Component.UI, untrusted, Event.START, Outcome.OBSERVED, ErrorCode.NONE));
        File snapshot = journal.snapshot(new File(ownDirectory, "rejected.tsv"), 5000);
        assertEquals(1, journal.status().dropped);
        assertFalse(DiagnosticReader.read(snapshot).complete);
        assertFalse(new String(Files.readAllBytes(snapshot.toPath()), StandardCharsets.UTF_8).contains("synthetic-content"));
    }
}

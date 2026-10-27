package org.openridesync.diagnostics;

import java.io.*;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.util.zip.ZipFile;
import static org.openridesync.diagnostics.DiagnosticJournal.*;

/** No test framework or Android runtime required: java ...DiagnosticsRegression [export.zip]. */
public final class DiagnosticsRegression {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void record(DiagnosticJournal j) { check(j.record(Component.APP, j.newOperation(), Event.START, Outcome.OBSERVED, ErrorCode.NONE), "accepted"); }
    private static void fill(DiagnosticJournal j) throws Exception {
        for (int i=0;i<100 && !j.status().sizeLimited;i++) {
            for (int n=0;n<32;n++) j.record(Component.BLE,null,Event.REQUEST,Outcome.STARTED,ErrorCode.NONE);
            j.flush(5000);
        }
        check(j.status().sizeLimited, "full before explicit archive");
        // Exhaust the checkpoint reserve too: archive must not depend on flush succeeding.
        int attempts = 0;
        while (j.flush(5000) && ++attempts < 100) { }
        check(attempts < 100 && !j.flush(5000), "full checkpoint reserve exhausted");
    }
    private static void archives(File root, File outputs) throws Exception {
        File directory = new File(root, "archive-resume");
        File live = new File(directory, FILE_NAME);
        File oldSnapshot = new File(root, "archive-old.tsv");
        Archive first;
        byte[] original;
        try (DiagnosticJournal j = new DiagnosticJournal(directory,128,4096)) {
            try { j.archiveAndResume(); throw new AssertionError("non-full archive"); } catch (IOException expected) { }
            String operation = j.newOperation();
            check(j.record(Component.APP,operation,Event.START,Outcome.OBSERVED,ErrorCode.NONE), "boundary record accepted");
            check(j.flush(5000), "boundary record persisted");
            fill(j);
            original = Files.readAllBytes(live.toPath());
            check(new String(original,java.nio.charset.StandardCharsets.UTF_8).contains(operation), "pre-boundary record retained");
            first = j.archiveAndResume();
            check(!first.complete && first.bytes == original.length && first.sessions == 1, "full archive remains incomplete");
            check(!j.status().sizeLimited && j.status().accepted == 0 && j.status().written == 0 &&
                j.status().dropped == 0 && j.status().writeFailures == 0, "session counters reset only after publish");
            check(DiagnosticReader.read(live).complete, "fresh live starts with a complete checkpoint");
            j.snapshotArchive(first.id, oldSnapshot);
            check(java.util.Arrays.equals(original,Files.readAllBytes(oldSnapshot.toPath())), "raw full bytes retained");
            check(first.sha256.equals(DiagnosticArchives.hash(oldSnapshot)), "archive metadata hash");
            check(j.archives().size() == 1 && j.archives().get(0).id.equals(first.id), "verified archive listed");
            try { j.archiveAndResume(); throw new AssertionError("implicit repeated rotation"); } catch (IOException expected) { }
            record(j); check(j.flush(5000), "same SDK journal reference continues writing");
            check(DiagnosticReader.read(j.snapshot(new File(root,"resumed.tsv"),5000)).complete, "resumed session complete");
            File exportDirectory = outputs == null ? root : outputs;
            check(exportDirectory.isDirectory() || exportDirectory.mkdirs(), "archive export output directory");
            DiagnosticExport.exportArchive(j,first.id,new File(exportDirectory,"archived-full.zip"),"org.openridesync.app",1,"abcdef0",36);
            DiagnosticExport.export(j,new File(exportDirectory,"resumed-complete.zip"),"org.openridesync.app",1,"abcdef0",36);
            File archived = new File(directory,"diagnostic-archives/"+first.id+".tsv");
            File metadata = new File(directory,"diagnostic-archives/"+first.id+".properties");
            File hardlink = new File(root,"archive-hardlink.tsv");
            Files.createLink(hardlink.toPath(),archived.toPath());
            for (File destination : new File[] {live, archived, metadata, hardlink, new File(directory,FILE_NAME+".lock"),
                    new File(directory,"diagnostic-archives/other.zip")}) {
                try { j.snapshotArchive(first.id,destination); throw new AssertionError("archive destination alias"); } catch (IOException expected) { }
                try { j.checkExportDestination(destination); throw new AssertionError("export destination alias"); } catch (IOException expected) { }
            }
            for (String id : new String[] {null,"../"+first.id,"AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA","missing"}) {
                try { j.snapshotArchive(id,oldSnapshot); throw new AssertionError("invalid archive ID"); } catch (IOException expected) { }
            }
            check(java.util.Arrays.equals(original,Files.readAllBytes(archived.toPath())), "alias rejection preserves archive");
            File orphan = new File(directory,"diagnostic-archives/"+java.util.UUID.randomUUID()+".tsv");
            Files.writeString(orphan.toPath(),"partial orphan");
            Files.writeString(new File(directory,"diagnostic-archives/"+java.util.UUID.randomUUID()+".properties.pending").toPath(),"partial metadata");
            check(j.archives().size() == 1, "orphan partial artifacts not listed");
            fill(j);
            j.snapshotArchive(first.id,oldSnapshot); // Full live checkpoint failure cannot block old evidence.
            check(java.util.Arrays.equals(original,Files.readAllBytes(oldSnapshot.toPath())), "archive snapshot independent of live flush");
            Archive second = j.archiveAndResume();
            check(!first.id.equals(second.id) && j.archives().size() == 2, "repeated explicit archive preserves both");
            record(j); check(j.flush(5000), "second resumed session");
        }
        try (DiagnosticJournal restarted = new DiagnosticJournal(directory,128,4096)) {
            record(restarted); check(restarted.flush(5000), "restart after archive");
            check(restarted.archives().size() == 2, "archives survive restart");
            restarted.snapshotArchive(first.id,oldSnapshot);
            check(java.util.Arrays.equals(original,Files.readAllBytes(oldSnapshot.toPath())), "old bytes unchanged across writes and restart");
            check(first.sha256.equals(DiagnosticArchives.hash(oldSnapshot)), "old hash unchanged after restart");
            restarted.close();
            restarted.snapshotArchive(first.id,oldSnapshot); // No active worker/flush dependency.
        }
        try (DiagnosticJournal queued = new DiagnosticJournal(new File(root,"archive-queued"),512,4096)) {
            synchronized (queued) { for (int i=0;i<512;i++) record(queued); }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!queued.status().sizeLimited && System.nanoTime() < deadline) Thread.sleep(1);
            check(queued.status().sizeLimited,"queued boundary reaches cap");
            Archive archive = queued.archiveAndResume();
            File snapshot = queued.snapshotArchive(archive.id,new File(root,"archive-queued.tsv"));
            java.util.List<String> rows = Files.readAllLines(snapshot.toPath());
            String[] counters = rows.get(rows.size()-1).split("\t");
            long physical = rows.stream().skip(1).filter(row -> !row.split("\t")[7].equals("CHECKPOINT")).count();
            check(counters[7].equals("CHECKPOINT") && Long.parseLong(counters[10]) == 512 &&
                Long.parseLong(counters[11]) == physical && Long.parseLong(counters[12]) == 512-physical,
                "all pre-boundary accepted rows drained and accounted; cap losses remain incomplete");
            check(!archive.complete && queued.status().accepted == 0 && queued.status().written == 0,
                "old queue never leaks into fresh session or becomes recovered evidence");
        }
        for (String kind : new String[] {"directory", "actual-lock", "sidecar-lock", "corrupt"}) {
            File failed = new File(root,"archive-failure-"+kind);
            try (DiagnosticJournal j = new DiagnosticJournal(failed,128,4096)) {
                fill(j);
                File source = new File(failed,FILE_NAME);
                if (kind.equals("directory")) Files.writeString(new File(failed,"diagnostic-archives").toPath(),"blocked");
                if (kind.equals("corrupt")) {
                    byte[] bytes = Files.readAllBytes(source.toPath()); bytes[0] = '!'; Files.write(source.toPath(),bytes);
                }
                byte[] before = Files.readAllBytes(source.toPath());
                File locked = new File(failed,kind.equals("sidecar-lock") ? FILE_NAME+".lock" : FILE_NAME);
                if (kind.endsWith("lock")) {
                    try (RandomAccessFile held = new RandomAccessFile(locked,"rw"); FileLock lock=held.getChannel().lock()) {
                        for (int retry=0;retry<16;retry++) {
                            try { j.archiveAndResume(); throw new AssertionError("locked archive"); }
                            catch (IOException expected) {
                                check(expected.getCause() != null, "completed failure releases archive ownership before explicit retry");
                            }
                        }
                    }
                } else {
                    try { j.archiveAndResume(); throw new AssertionError("failed archive"); } catch (IOException expected) { }
                }
                check(j.status().sizeLimited, "failure cannot reset full state");
                check(java.util.Arrays.equals(before,Files.readAllBytes(source.toPath())), "failure cannot replace old evidence");
                if (!kind.equals("directory")) check(j.archives().isEmpty(), "failed partial archives not listed");
                if (kind.endsWith("lock")) check(j.archiveAndResume() != null, "explicit retry after lock release");
            }
        }
    }
    private static void authGolden(File root) throws Exception {
        // Synthetic workflow golden: only closed method/outcome/status names and UUID correlation.
        String[] events = {
            "AUTH\tTOKEN_READ\tSTARTED\tNONE", "AUTH\tTOKEN_READ\tSUCCEEDED\tNONE",
            "API\tHONDA_GET_USER\tSTARTED\tNONE", "API\tHONDA_GET_USER\tFAILED\tNOT_FOUND",
            "AUTH\tTOKEN_REFRESH\tSTARTED\tNONE", "AUTH\tTOKEN_REFRESH\tFAILED\tUNAUTHORIZED",
            "API\tHONDA_GET_COUNTRY\tSTARTED\tNONE", "API\tHONDA_GET_COUNTRY\tFAILED\tPERMISSION_DENIED",
            "API\tHONDA_LIST_MODEL_CATALOGS\tSTARTED\tNONE", "API\tHONDA_LIST_MODEL_CATALOGS\tSUCCEEDED\tNONE",
            "API\tHONDA_LIST_MODEL_SERIES\tSTARTED\tNONE", "API\tHONDA_LIST_MODEL_SERIES\tSUCCEEDED\tNONE",
            "API\tHONDA_LIST_MODELS\tSTARTED\tNONE", "API\tHONDA_LIST_MODELS\tSUCCEEDED\tNONE",
            "API\tHONDA_GET_IMAGE\tSTARTED\tNONE", "API\tHONDA_GET_IMAGE\tFAILED\tNOT_FOUND",
            "API\tHONDA_GET_SETUP_PAGE\tSTARTED\tNONE", "API\tHONDA_GET_SETUP_PAGE\tSUCCEEDED\tNONE",
            "API\tHONDA_GET_TUTORIAL_PAGE\tSTARTED\tNONE", "API\tHONDA_GET_TUTORIAL_PAGE\tSUCCEEDED\tNONE",
            "API\tGUIDE_DOWNLOAD\tSTARTED\tNONE", "API\tGUIDE_DOWNLOAD\tFAILED\tNETWORK",
            "API\tHONDA_LIST_DAILY_TRIPS\tSTARTED\tNONE", "API\tHONDA_LIST_DAILY_TRIPS\tSUCCEEDED\tNONE",
            "API\tHONDA_LIST_TRIP_DETAILS\tSTARTED\tNONE", "API\tHONDA_LIST_TRIP_DETAILS\tSUCCEEDED\tNONE",
            "API\tHONDA_GET_TRIP_DETAIL\tSTARTED\tNONE", "API\tHONDA_GET_TRIP_DETAIL\tFAILED\tNOT_FOUND",
            "API\tHONDA_GET_WEATHER\tSTARTED\tNONE", "API\tHONDA_GET_WEATHER\tSUCCEEDED\tNONE",
            "API\tHONDA_GET_WEATHER_FORECAST\tSTARTED\tNONE", "API\tHONDA_GET_WEATHER_FORECAST\tFAILED\tUNAVAILABLE",
            // Synthetic callback stages, never vehicle/function acceptance.
            "BLE\tBLE_SCAN\tSTARTED\tNONE", "BLE\tBLE_SCAN\tOBSERVED\tNONE",
            "BLE\tBLE_BOND\tSTARTED\tNONE", "BLE\tBLE_BOND\tSUCCEEDED\tNONE",
            "BLE\tBLE_GATT_CONNECT\tSTARTED\tNONE", "BLE\tBLE_GATT_CONNECT\tSUCCEEDED\tNONE",
            "BLE\tBLE_DISCOVER_SERVICES\tSTARTED\tNONE", "BLE\tBLE_DISCOVER_SERVICES\tSUCCEEDED\tNONE",
            "BLE\tBLE_CCCD_WRITE\tSTARTED\tNONE", "BLE\tBLE_CCCD_WRITE\tSUCCEEDED\tNONE",
            "BLE\tBLE_SECURITY_STATUS\tOBSERVED\tNONE",
            "BLE\tBLE_SECURITY_RSA_EXCHANGE\tSTARTED\tNONE", "BLE\tBLE_SECURITY_RSA_EXCHANGE\tSUCCEEDED\tNONE",
            "BLE\tBLE_SECURITY_AES_VERIFY\tSTARTED\tNONE", "BLE\tBLE_SECURITY_AES_VERIFY\tFAILED\tUNAUTHORIZED",
            "BLE\tDISCONNECTED\tOBSERVED\tNONE",
            "UI\tPAIRING_ASSOCIATION\tSTARTED\tNONE", "UI\tPAIRING_ASSOCIATION\tSUCCEEDED\tNONE",
            "BLE\tBLE_BTU_INFORMATION\tSTARTED\tNONE", "BLE\tBLE_BTU_INFORMATION\tSUCCEEDED\tNONE",
            "UI\tPAIRED_VEHICLE_SAVE\tSTARTED\tNONE", "UI\tPAIRED_VEHICLE_SAVE\tFAILED\tIO",
            "BLE\tBLE_CTS_START\tSTARTED\tNONE", "BLE\tBLE_CTS_START\tSUCCEEDED\tNONE",
            "BLE\tBLE_CTS_REQUEST\tOBSERVED\tNONE", "BLE\tBLE_CTS_NOTIFY\tFAILED\tIO",
            "BLE\tBLE_ASSIGNMENT_REGISTER\tSTARTED\tNONE", "BLE\tBLE_ASSIGNMENT_REGISTER\tFAILED\tTIMEOUT",
            "BLE\tBLE_CONTROL_COMMAND\tCANCELLED\tABORTED", "BLE\tBLE_CONTROL_RECEIPT\tFAILED\tFAILED_PRECONDITION",
            "BLE\tBLE_RUNTIME_INIT\tSTARTED\tNONE", "BLE\tBLE_FUNCTION_NOTIFICATION\tOBSERVED\tNONE",
            "BLE\tBLE_DRIVING_NOTIFICATION\tFAILED\tDATA_LOSS",
            "BLE\tBLE_RUNTIME_SERVICE\tSTARTED\tNONE", "BLE\tBLE_RUNTIME_STATE\tOBSERVED\tNONE",
            "BLE\tBLE_RUNTIME_STOP\tCANCELLED\tCANCELLED",
            "MEDIA\tMEDIA_ACCESS\tFAILED\tPERMISSION_DENIED",
            "MEDIA\tMEDIA_BROWSER\tSTARTED\tNONE", "MEDIA\tMEDIA_BROWSER\tOBSERVED\tNONE",
            "MEDIA\tMEDIA_BROWSER\tFAILED\tFAILED_PRECONDITION",
            "MEDIA\tMEDIA_SESSION\tOBSERVED\tNONE",
            // Dispatch is not playback observation or audible output.
            "MEDIA\tMEDIA_PLAY\tSTARTED\tNONE", "MEDIA\tMEDIA_PLAY\tOBSERVED\tNONE",
            "MEDIA\tMEDIA_PAUSE\tFAILED\tUNAVAILABLE",
            "MEDIA\tMEDIA_PREVIOUS\tFAILED\tUNSUPPORTED", "MEDIA\tMEDIA_NEXT\tOBSERVED\tNONE"
        };
        String operation = "00000000-0000-0000-0000-000000000001";
        try (DiagnosticJournal journal = new DiagnosticJournal(new File(root, "auth-golden"))) {
            for (String row : events) {
                String[] fields = row.split("\t");
                check(journal.record(Component.valueOf(fields[0]), operation, Event.valueOf(fields[1]),
                    Outcome.valueOf(fields[2]), ErrorCode.valueOf(fields[3])), "auth golden accepted");
            }
            File snapshot = journal.snapshot(new File(root, "auth-golden.tsv"), 5000);
            DiagnosticReader.Result result = DiagnosticReader.read(snapshot);
            check(result.complete && result.rows == events.length + 1, "auth golden reader complete");
            java.util.List<String> rows = Files.readAllLines(snapshot.toPath());
            for (int i = 0; i < events.length; i++) {
                String[] fields = rows.get(i + 1).split("\t");
                check((fields[5]+"\t"+fields[7]+"\t"+fields[8]+"\t"+fields[9]).equals(events[i]), "auth golden method/status order");
                check(fields[6].equals(operation), "one correlation across auth and RPC");
            }
            String content = Files.readString(snapshot.toPath());
            check(!content.contains("Bearer") && !content.contains("googleIdToken"), "no credential fields");
            String[] rejectedOperations = {"modelId=synthetic-model", "imageId=synthetic-image", "accountId=synthetic-account",
                "https://synthetic.invalid/setup?private=value", "modelLabel=synthetic-model", "synthetic@example.invalid", "Bearer synthetic-token", "tripId=synthetic-trip",
                "02:00:00:00:00:00", "deviceName=synthetic-meter", "key=synthetic-private-key", "challenge=synthetic-private-challenge", "sessionPayload=synthetic-private-payload"};
            for (String raw : rejectedOperations) {
                check(!journal.record(Component.BLE, raw, Event.BLE_SECURITY_AES_VERIFY, Outcome.STARTED, ErrorCode.NONE), "raw identifier correlation rejected");
            }
            File rejected = journal.snapshot(new File(root, "catalog-privacy.tsv"), 5000);
            String privateCheck = Files.readString(rejected.toPath());
            check(!privateCheck.contains("modelId") && !privateCheck.contains("imageId") && !privateCheck.contains("accountId"), "no raw catalog/image/account identifiers");
            for (String raw : rejectedOperations) check(!privateCheck.contains(raw), "no arbitrary operation text persisted");
            check(journal.status().dropped == rejectedOperations.length && !DiagnosticReader.read(rejected).complete, "identifier rejection visible");
            Files.writeString(snapshot.toPath(), content.replace("\tNOT_FOUND\t", "\tBearer_secret\t"));
            try { DiagnosticReader.read(snapshot); throw new AssertionError("arbitrary status accepted"); }
            catch (IOException expected) { }
        }
    }
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("openridesync-diagnostics-test-").toFile();
        authGolden(root);
        archives(root,args.length > 1 ? new File(args[1]) : null);
        try (DiagnosticJournal j = new DiagnosticJournal(new File(root, "normal"))) {
            record(j); check(j.flush(5000), "flush");
            File snapshot = j.snapshot(new File(root, "snapshot.tsv"), 5000);
            check(DiagnosticReader.read(snapshot).complete, "complete persisted snapshot");
            check(j.status().written == 1, "written counter");
            record(j); record(j); j.snapshot(snapshot,5000);
            java.util.List<String> rows = Files.readAllLines(snapshot.toPath());
            rows.remove(2); Files.write(snapshot.toPath(), rows);
            check(!DiagnosticReader.read(snapshot).complete,"deleted middle row cannot be complete");
            rows.remove(1); Files.write(snapshot.toPath(), rows);
            check(!DiagnosticReader.read(snapshot).complete,"missing initial record cannot be complete");
            String secret = "Bearer secret-token\tnotification body\n";
            check(!j.record(Component.AUTH, secret, Event.RESPONSE, Outcome.FAILED, ErrorCode.NONE), "reject secret correlation");
            check(j.flush(5000), "drop checkpoint"); j.snapshot(snapshot, 5000);
            check(!Files.readString(snapshot.toPath()).contains("secret"), "no payload");
            check(!DiagnosticReader.read(snapshot).complete, "drop marked incomplete");
            File zip = args.length > 0 ? new File(args[0]) : new File(root, "export.zip");
            DiagnosticExport.export(j, zip, "org.openridesync.app", 1, "abcdef0", 36);
            try (ZipFile in = new ZipFile(zip)) { check(in.size()==2, "zip members"); }
            try { DiagnosticExport.export(j, new File(root,"normal/"+FILE_NAME), "org.openridesync.app",1,"unknown",36); throw new AssertionError("alias"); } catch(IOException expected) {}
            Files.writeString(snapshot.toPath(), "broken");
            try { DiagnosticReader.read(snapshot); throw new AssertionError("corrupt accepted"); } catch(IOException expected) {}
        }
        try (DiagnosticJournal j = new DiagnosticJournal(new File(root,"bounded"), 2, 8192)) {
            synchronized (j) {
                record(j); record(j);
                check(!j.record(Component.UI,null,Event.REQUEST,Outcome.STARTED,ErrorCode.NONE),"queue bounded");
            }
            check(j.flush(5000),"bounded flush"); check(j.status().dropped==1,"drop counter");
            check(!DiagnosticReader.read(j.snapshot(new File(root,"bounded.tsv"),5000)).complete,"persist drop");
        }
        File lockDir = new File(root,"locked");
        try (DiagnosticJournal j = new DiagnosticJournal(lockDir)) {
            record(j); check(j.flush(5000),"initial lock flush");
            try(RandomAccessFile file = new RandomAccessFile(new File(lockDir,FILE_NAME),"rw"); FileLock lock=file.getChannel().lock()) {
                record(j); check(!j.flush(5000),"lock failure exposed");
            }
            check(j.status().writeFailures>=1,"failure count");
            check(j.flush(5000),"recovery checkpoint");
            check(!DiagnosticReader.read(j.snapshot(new File(root,"locked.tsv"),5000)).complete,"failure persisted");
            Thread.currentThread().interrupt();
            record(j); check(!j.flush(5000),"interrupted flush returns");
            check(Thread.currentThread().isInterrupted(),"interrupt preserved"); Thread.interrupted();
            check(j.flush(5000),"writer survives caller interruption");
        }
        try (DiagnosticJournal j = new DiagnosticJournal(new File(root,"cap"),128,4096)) {
            for(int i=0;i<30;i++) { j.record(Component.BLE,null,Event.REQUEST,Outcome.STARTED,ErrorCode.NONE); }
            check(j.flush(5000),"reserved checkpoint");
            check(j.status().sizeLimited,"cap visible");
            check(new File(root,"cap/"+FILE_NAME).length()<=4096,"bounded file");
            check(!DiagnosticReader.read(j.snapshot(new File(root,"cap.tsv"),5000)).complete,"cap incomplete");
        }
        File blocker = new File(root,"blocked"); Files.writeString(blocker.toPath(),"file not directory");
        try(DiagnosticJournal j = new DiagnosticJournal(blocker)) {
            record(j); check(!j.flush(5000),"unwritable visible"); check(j.status().writeFailures>0,"unwritable count");
        }
        File partial = new File(root,"partial"); partial.mkdirs();
        Files.writeString(new File(partial,FILE_NAME).toPath(),HEADER+"\n1\tpartial");
        try(DiagnosticJournal j = new DiagnosticJournal(partial)) {
            record(j); check(!j.flush(5000),"partial existing file fails closed");
            check(Files.readString(new File(partial,FILE_NAME).toPath()).endsWith("partial"),"partial evidence preserved");
        }
        File empty = new File(root,"empty"); empty.mkdirs();
        check(new File(empty,FILE_NAME).createNewFile(),"existing zero-byte file");
        try(DiagnosticJournal j = new DiagnosticJournal(empty)) {
            record(j); check(j.flush(5000),"zero-byte journal initialized");
            check(DiagnosticReader.read(j.snapshot(new File(root,"empty.tsv"),5000)).complete,"zero-byte recovery verified");
        }
        DiagnosticJournal closed = new DiagnosticJournal(new File(root,"closed")); record(closed); closed.close();
        check(closed.status().closed && closed.status().written==1,"close drains");
        check(!closed.record(Component.APP,null,Event.STOP,Outcome.OBSERVED,ErrorCode.NONE),"closed rejects");
        check(DiagnosticReader.read(new File(root,"closed/"+FILE_NAME)).complete,"close checkpoint");
        try(DiagnosticJournal restarted = new DiagnosticJournal(new File(root,"closed"))) {
            record(restarted); check(restarted.flush(5000),"restart flush");
            DiagnosticReader.Result result = DiagnosticReader.read(restarted.snapshot(new File(root,"restart.tsv"),5000));
            check(result.sessions==2 && result.complete,"retains sessions across restart");
        }
        System.out.println("DiagnosticsRegression: persistence, export, corruption, queue, privacy, locks, recovery, interrupt, cap, close passed");
    }
}

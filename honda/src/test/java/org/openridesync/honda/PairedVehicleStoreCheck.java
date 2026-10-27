package org.openridesync.honda;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import org.openridesync.honda.wire.HondaWire.*;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** Synthetic garage persistence only; no device, account, bond, or vehicle action. */
public final class PairedVehicleStoreCheck {
    private interface Action { void run() throws Exception; }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static void rejects(Action action) throws Exception {
        try { action.run(); throw new AssertionError("invalid garage accepted"); } catch(IOException expected) { }
    }
    private static void sameMetadata(PairedVehicleStore.Vehicle actual,PairedVehicleStore.Vehicle expected) {
        check(actual.mac.equals(expected.mac) && actual.btuName.equals(expected.btuName)
            && actual.seriesCode.equals(expected.seriesCode) && actual.firmwareVersion.equals(expected.firmwareVersion)
            && actual.vehicleName.equals(expected.vehicleName) && actual.year==expected.year && actual.state==expected.state
            && Arrays.equals(actual.model.toByteArray(),expected.model.toByteArray()),"mutation retains all metadata and unknown protobuf fields");
    }
    private static void upsert(PairedVehicleStore store,PairedVehicleStore.Vehicle vehicle) throws IOException {
        store.upsert(vehicle.mac,vehicle.btuName,vehicle.seriesCode,vehicle.firmwareVersion,vehicle.vehicleName,
            vehicle.model,vehicle.year,vehicle.state);
    }
    /** Valid digest around deliberately invalid content exercises structural checks, not only hashing. */
    private static byte[] fixture(List<PairedVehicleStore.Vehicle> vehicles,boolean duplicateMac,int... focus) throws Exception {
        ByteArrayOutputStream payload=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(payload)) {
            out.writeInt(vehicles.size());
            for(int i=0;i<vehicles.size();i++) {
                var vehicle=vehicles.get(i);
                out.writeUTF(duplicateMac ? vehicles.get(0).mac : vehicle.mac);
                out.writeUTF(vehicle.btuName); out.writeUTF(vehicle.seriesCode); out.writeUTF(vehicle.firmwareVersion);
                out.writeUTF(vehicle.vehicleName); out.writeUTF(vehicle.state.name()); out.writeByte(focus[i]); out.writeInt(vehicle.year);
                byte[] proto=vehicle.model.toByteArray(); out.writeInt(proto.length); out.write(proto);
            }
        }
        byte[] bytes=payload.toByteArray(); ByteArrayOutputStream snapshot=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(snapshot)) {
            out.writeInt(0x4f525056); out.writeInt(1); out.writeInt(bytes.length);
            out.write(MessageDigest.getInstance("SHA-256").digest(bytes)); out.write(bytes);
        }
        return snapshot.toByteArray();
    }
    private static void rejectsPreserving(PairedVehicleStore store,File file,byte[] invalid,
            PairedVehicleStore.Vehicle vehicle,String absent) throws Exception {
        Files.write(file.toPath(),invalid);
        rejects(store::read);
        rejects(() -> upsert(store,vehicle));
        rejects(() -> store.setFocus(vehicle.mac));
        rejects(() -> store.forget(vehicle.mac));
        rejects(() -> store.setFocus(absent));
        rejects(() -> store.forget(absent));
        check(Arrays.equals(invalid,Files.readAllBytes(file.toPath())),"every mutation preserves corrupt snapshot, including absent targets");
    }
    public static void main(String[] args) throws Exception {
        File root=Files.createTempDirectory("paired-vehicle-check-").toFile();
        File accountA=new File(root,"synthetic-account-a-sha"), accountB=new File(root,"synthetic-account-b-sha");
        PairedVehicleStore store=new PairedVehicleStore(accountA), other=new PairedVehicleStore(accountB);
        String first="02:AA:BB:CC:DD:01", second="02:AA:BB:CC:DD:02", absent="02:AA:BB:CC:DD:03";
        UnknownFieldSet unknown=UnknownFieldSet.newBuilder().addField(100,UnknownFieldSet.Field.newBuilder().addVarint(77).build()).build();
        Model model=Model.newBuilder().setModelLabel("model/one").setModelSeriesLabel("series/one").addModelYears(2025)
            .setUnknownFields(unknown).setMeter(Meter.newBuilder().setGeneration(99).setType(123)).build();
        check(store.read().isEmpty() && other.read().isEmpty(),"fresh account isolation");
        check(!store.setFocus(first) && !store.forget(first) && !accountA.exists(),"missing targets do not create storage");
        rejects(() -> store.setFocus(null)); rejects(() -> store.forget("invalid"));
        check(!accountA.exists(),"invalid targets do not create storage");
        store.upsert(first.toLowerCase(Locale.ROOT),"synthetic BTU","series-code","firmware","vehicle",model,2025,PairedVehicleStore.State.PAIRED);
        var saved=new PairedVehicleStore(accountA).read();
        check(saved.size()==1 && saved.get(0).mac.equals(first) && saved.get(0).inFocus,"normalized MAC and restart");
        check(saved.get(0).state==PairedVehicleStore.State.PAIRED,"PAIRED does not imply READY");
        check(Arrays.equals(saved.get(0).model.toByteArray(),model.toByteArray()),"complete protobuf unknown/raw meter retained");
        check(saved.get(0).btuName.equals("synthetic BTU") && saved.get(0).seriesCode.equals("series-code") && saved.get(0).firmwareVersion.equals("firmware") && saved.get(0).vehicleName.equals("vehicle"),"complete metadata retained");
        Model secondModel=model.toBuilder().setModelLabel("model/two").build();
        store.upsert(second,"second BTU","series-code","firmware2","second",secondModel,0,PairedVehicleStore.State.TUTORIAL_PENDING);
        saved=store.read();
        check(saved.size()==2 && !saved.get(0).inFocus && saved.get(1).inFocus,"new focus preserves old garage");
        check(saved.get(1).year==0,"physical vehicle year can remain unknown");
        var beforeFocus=saved;
        check(store.setFocus(first.toLowerCase(Locale.ROOT)),"full lower-case MAC selects existing vehicle");
        saved=new PairedVehicleStore(accountA).read();
        check(saved.size()==2 && saved.get(0).inFocus && !saved.get(1).inFocus,"first focus persists across restart");
        sameMetadata(saved.get(0),beforeFocus.get(0)); sameMetadata(saved.get(1),beforeFocus.get(1));
        File file=new File(accountA,"paired-vehicles-v1.bin");
        byte[] focused=Files.readAllBytes(file.toPath());
        check(store.setFocus(first),"repeated focus is idempotent success");
        check(Arrays.equals(focused,Files.readAllBytes(file.toPath())),"repeated focus leaves snapshot unchanged");
        check(store.setFocus(second),"select second saved vehicle");
        saved=new PairedVehicleStore(accountA).read();
        check(!saved.get(0).inFocus && saved.get(1).inFocus,"focus moves only to exact target");
        sameMetadata(saved.get(0),beforeFocus.get(0)); sameMetadata(saved.get(1),beforeFocus.get(1));
        store.upsert(first,"renamed BTU","series-code","updated-fw","renamed",model,0,PairedVehicleStore.State.PAIRED);
        saved=store.read();
        check(saved.size()==2 && saved.get(0).inFocus && !saved.get(1).inFocus && saved.get(0).btuName.equals("renamed BTU"),"MAC upsert dedup and refocus");
        check(other.read().isEmpty(),"account A never appears in B");
        other.upsert(first,"other account BTU","series-code","other-fw","other",secondModel,2025,PairedVehicleStore.State.REPAIR_NEEDED);
        check(store.read().get(0).model.equals(model) && other.read().get(0).model.equals(secondModel),"same MAC independently scoped by account");
        File otherFile=new File(accountB,"paired-vehicles-v1.bin"); byte[] otherPrior=Files.readAllBytes(otherFile.toPath());
        byte[] prior=Files.readAllBytes(file.toPath());
        rejects(() -> store.upsert(first,"name","series-code","fw","vehicle",model,2026,PairedVehicleStore.State.PAIRED));
        rejects(() -> store.upsert("invalid","name","series-code","fw","vehicle",model,0,PairedVehicleStore.State.PAIRED));
        rejects(() -> store.upsert(first,"x".repeat(1025),"series-code","fw","vehicle",model,0,PairedVehicleStore.State.PAIRED));
        UnknownFieldSet huge=UnknownFieldSet.newBuilder().addField(101,UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.copyFrom(new byte[8*1024*1024])).build()).build();
        rejects(() -> store.upsert(first,"name","series-code","fw","vehicle",model.toBuilder().mergeUnknownFields(huge).build(),0,PairedVehicleStore.State.PAIRED));
        check(!store.setFocus(absent) && !store.forget(absent),"unknown full MAC explicitly refused");
        for(String invalid:new String[]{null,"", "02:AA:BB:CC:DD",first+":FF"," "+first,first+" ","02-AA-BB-CC-DD-01","02:AA:BB:CC:DD:0G"}) {
            rejects(() -> store.setFocus(invalid)); rejects(() -> store.forget(invalid));
        }
        check(Arrays.equals(prior,Files.readAllBytes(file.toPath())),"invalid and oversized update preserves previous snapshot");

        var beforeForget=store.read();
        check(store.forget(second.toLowerCase(Locale.ROOT)),"forget accepts full normalized MAC");
        saved=new PairedVehicleStore(accountA).read();
        check(saved.size()==1 && saved.get(0).inFocus,"forget non-primary leaves primary unchanged");
        sameMetadata(saved.get(0),beforeForget.get(0));
        byte[] afterForget=Files.readAllBytes(file.toPath());
        check(!store.forget(second) && !store.setFocus(second),"repeated forgotten target remains missing");
        check(Arrays.equals(afterForget,Files.readAllBytes(file.toPath())),"missing targets preserve snapshot");

        upsert(store,beforeForget.get(1)); check(store.setFocus(first),"restore first focus");
        check(store.forget(first),"forget primary");
        saved=new PairedVehicleStore(accountA).read();
        check(saved.size()==1 && saved.get(0).mac.equals(second) && !saved.get(0).inFocus,"primary removal persists without choosing another vehicle");
        sameMetadata(saved.get(0),beforeForget.get(1));
        check(store.setFocus(second),"explicit selection restores focus from zero-focus snapshot");
        saved=new PairedVehicleStore(accountA).read();
        check(saved.size()==1 && saved.get(0).inFocus,"restored focus persists"); sameMetadata(saved.get(0),beforeForget.get(1));
        check(store.forget(second),"forget last vehicle");
        check(file.isFile() && file.length()==48 && new PairedVehicleStore(accountA).read().isEmpty(),"last removal atomically persists empty versioned snapshot without deleting file");
        byte[] empty=Files.readAllBytes(file.toPath());
        check(!store.forget(second) && !store.setFocus(first),"empty garage refuses missing mutations");
        check(Arrays.equals(empty,Files.readAllBytes(file.toPath())),"empty snapshot preserved by repeated mutations");
        upsert(store,beforeForget.get(0));
        saved=new PairedVehicleStore(accountA).read();
        check(saved.size()==1 && saved.get(0).inFocus,"upsert after empty snapshot succeeds"); sameMetadata(saved.get(0),beforeForget.get(0));
        upsert(store,beforeForget.get(1)); check(store.setFocus(first),"restore two-vehicle garage");
        check(Arrays.equals(prior,Files.readAllBytes(file.toPath())),"complete mutation round trip restores original snapshot bytes");
        check(Arrays.equals(otherPrior,Files.readAllBytes(otherFile.toPath())),"focus and forget never alter another account with same MAC");

        Files.write(file.toPath(),fixture(beforeForget,false,0,0));
        saved=new PairedVehicleStore(accountA).read();
        check(saved.size()==2 && !saved.get(0).inFocus && !saved.get(1).inFocus,"multiple saved vehicles may have no primary");
        sameMetadata(saved.get(0),beforeForget.get(0)); sameMetadata(saved.get(1),beforeForget.get(1));
        check(store.forget(first),"forget from zero-focus garage");
        saved=new PairedVehicleStore(accountA).read();
        check(saved.size()==1 && !saved.get(0).inFocus,"forget does not introduce primary into zero-focus garage");
        sameMetadata(saved.get(0),beforeForget.get(1));
        check(store.forget(second) && new PairedVehicleStore(accountA).read().isEmpty(),"forget unfocused last vehicle persists empty garage");

        byte[] corrupt=prior.clone(); corrupt[corrupt.length-1]^=1;
        rejectsPreserving(store,file,corrupt,beforeForget.get(0),absent);
        rejectsPreserving(store,file,Arrays.copyOf(prior,prior.length-1),beforeForget.get(0),absent);
        rejectsPreserving(store,file,fixture(beforeForget,false,1,1),beforeForget.get(0),absent);
        rejectsPreserving(store,file,fixture(beforeForget,true,1,0),beforeForget.get(0),absent);
        rejectsPreserving(store,file,fixture(beforeForget,false,2,0),beforeForget.get(0),absent);
        Files.write(file.toPath(),prior);
        check(store.read().size()==2,"previous snapshot remains readable");
        check(other.forget(first) && other.read().isEmpty(),"other account can forget same MAC independently");
        check(Arrays.equals(prior,Files.readAllBytes(file.toPath())),"other account removal leaves first account unchanged");
        for(File directory:new File[]{accountA,accountB}) {
            check(directory.listFiles().length==1,"no orphan temps or raw-address paths");
            check(new File(directory,"paired-vehicles-v1.bin").delete() && directory.delete(),"cleanup own fixtures");
        }
        check(root.delete(),"cleanup own root");
        System.out.println("PASS paired garage upsert, exact-MAC focus/forget, zero-focus/empty snapshots, account isolation, unknown protobuf, corruption and failure preservation");
    }
}

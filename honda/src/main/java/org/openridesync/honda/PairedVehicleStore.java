package org.openridesync.honda;

import org.openridesync.honda.wire.HondaWire.Model;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Private garage metadata. Caller supplies a verified UID SHA-256 directory; never logs data. */
public final class PairedVehicleStore {
    private static final int MAGIC=0x4f525056, VERSION=1, LIMIT=8*1024*1024, MAX_VEHICLES=64;
    // ponytail: one app-process lock; per-directory locks only if garage writes become concurrent work.
    private static final Object LOCK=new Object();
    private final File directory;
    public enum State { REPAIR_NEEDED, PAIRED, VEHICLE_SELECTION, TUTORIAL_PENDING, DFU_CHECK_PENDING, READY, REGULAR_UPDATE_NEEDED, CRITICAL_UPDATE_NEEDED }
    public static final class Vehicle {
        public final String mac, btuName, seriesCode, firmwareVersion, vehicleName;
        public final Model model;
        public final int year;
        public final State state;
        public final boolean inFocus;
        private Vehicle(String mac,String btuName,String seriesCode,String firmwareVersion,String vehicleName,
                Model model,int year,State state,boolean focus) {
            this.mac=mac; this.btuName=btuName; this.seriesCode=seriesCode; this.firmwareVersion=firmwareVersion;
            this.vehicleName=vehicleName; this.model=model; this.year=year; this.state=state; this.inFocus=focus;
        }
        private Vehicle focus(boolean value) { return new Vehicle(mac,btuName,seriesCode,firmwareVersion,vehicleName,model,year,state,value); }
    }
    public PairedVehicleStore(File directory) { this.directory=Objects.requireNonNull(directory); }
    public List<Vehicle> read() throws IOException { synchronized(LOCK) { return readFile(); } }

    /** Year zero means this physical vehicle's year is unknown, even if metadata lists possibilities. */
    public void upsert(String mac,String btuName,String seriesCode,String firmwareVersion,String vehicleName,
            Model model,int year,State state) throws IOException {
        synchronized(LOCK) {
            Vehicle incoming=new Vehicle(normalize(mac),btuName,seriesCode,firmwareVersion,vehicleName,model,year,state,true);
            validate(incoming);
            List<Vehicle> vehicles=new ArrayList<>(); boolean replaced=false;
            for(Vehicle old:readFile()) {
                if(old.mac.equals(incoming.mac)) { vehicles.add(incoming); replaced=true; }
                else vehicles.add(old.focus(false));
            }
            if(!replaced) vehicles.add(incoming);
            writeVehicles(vehicles);
        }
    }
    /**
     * Selects an exact saved MAC without changing its metadata. Already focused returns true
     * without rewriting; unknown addresses return false. Invalid targets or snapshots throw.
     */
    public boolean setFocus(String mac) throws IOException {
        synchronized(LOCK) {
            String address=normalize(mac);
            List<Vehicle> saved=readFile();
            Vehicle target=null;
            for(Vehicle vehicle:saved) if(vehicle.mac.equals(address)) { target=vehicle; break; }
            if(target==null) return false;
            if(target.inFocus) return true;
            List<Vehicle> vehicles=new ArrayList<>(saved.size());
            for(Vehicle vehicle:saved) vehicles.add(vehicle.focus(vehicle.mac.equals(address)));
            writeVehicles(vehicles);
            return true;
        }
    }
    /**
     * Forgets only this account's local metadata for an exact saved MAC. Does not alter OS bonds,
     * keys, associations, or remote state. Removing the focused vehicle does not select another.
     * Unknown addresses return false; removing the last vehicle persists an empty snapshot.
     */
    public boolean forget(String mac) throws IOException {
        synchronized(LOCK) {
            String address=normalize(mac);
            List<Vehicle> vehicles=new ArrayList<>(readFile());
            if(!vehicles.removeIf(vehicle -> vehicle.mac.equals(address))) return false;
            writeVehicles(vehicles);
            return true;
        }
    }
    private void writeVehicles(List<Vehicle> vehicles) throws IOException {
        if(vehicles.size()>MAX_VEHICLES) throw invalid();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(bytes)) {
            out.writeInt(vehicles.size());
            for(Vehicle vehicle:vehicles) {
                out.writeUTF(vehicle.mac); out.writeUTF(vehicle.btuName); out.writeUTF(vehicle.seriesCode);
                out.writeUTF(vehicle.firmwareVersion); out.writeUTF(vehicle.vehicleName); out.writeUTF(vehicle.state.name());
                out.writeBoolean(vehicle.inFocus); out.writeInt(vehicle.year);
                byte[] proto=vehicle.model.toByteArray(); out.writeInt(proto.length); out.write(proto);
                if(bytes.size()>LIMIT-44) throw invalid();
            }
        }
        write(bytes.toByteArray());
    }
    private File target() throws IOException {
        File target=new File(directory,"paired-vehicles-v1.bin");
        if(Files.isSymbolicLink(target.toPath())) throw invalid();
        return target;
    }
    private List<Vehicle> readFile() throws IOException {
        File file=target(); if(!file.exists()) return List.of();
        if(!file.isFile() || file.length()<48 || file.length()>LIMIT) throw invalid();
        try(DataInputStream in=new DataInputStream(new FileInputStream(file))) {
            if(in.readInt()!=MAGIC || in.readInt()!=VERSION) throw invalid();
            int length=in.readInt();
            if(length<4 || length>LIMIT-44 || file.length()!=44L+length) throw invalid();
            byte[] digest=new byte[32], bytes=new byte[length]; in.readFully(digest); in.readFully(bytes);
            if(in.read()!=-1 || !MessageDigest.isEqual(digest,hash(bytes))) throw invalid();
            try(DataInputStream payload=new DataInputStream(new ByteArrayInputStream(bytes))) {
                int count=payload.readInt(); if(count<0 || count>MAX_VEHICLES) throw invalid();
                List<Vehicle> vehicles=new ArrayList<>(); Set<String> addresses=new HashSet<>(); int focused=0;
                for(int i=0;i<count;i++) {
                    String mac=payload.readUTF(), name=payload.readUTF(), series=payload.readUTF(), firmware=payload.readUTF(), vehicleName=payload.readUTF();
                    State state=State.valueOf(payload.readUTF());
                    int focus=payload.readUnsignedByte(); if(focus>1) throw invalid();
                    int year=payload.readInt(), size=payload.readInt();
                    if(size<0 || size>payload.available()) throw invalid();
                    byte[] proto=new byte[size]; payload.readFully(proto);
                    Vehicle vehicle=new Vehicle(mac,name,series,firmware,vehicleName,Model.parseFrom(proto),year,state,focus==1);
                    validate(vehicle);
                    if(!mac.equals(normalize(mac)) || !addresses.add(mac)) throw invalid();
                    if(vehicle.inFocus) focused++;
                    vehicles.add(vehicle);
                }
                if(payload.available()!=0 || focused>1) throw invalid();
                return List.copyOf(vehicles);
            }
        } catch(IllegalArgumentException failure) { throw invalid(); }
    }
    private static void validate(Vehicle vehicle) throws IOException {
        text(vehicle.btuName,1024,true); text(vehicle.seriesCode,512,false);
        text(vehicle.firmwareVersion,256,true); text(vehicle.vehicleName,1024,true);
        if(vehicle.state==null || vehicle.model==null || vehicle.model.getSerializedSize()>LIMIT-44) throw invalid();
        text(vehicle.model.getModelLabel(),512,false); text(vehicle.model.getModelSeriesLabel(),512,false);
        if(vehicle.year<0 || (vehicle.year!=0 && !vehicle.model.getModelYearsList().contains(vehicle.year))) throw invalid();
        for(int year:vehicle.model.getModelYearsList()) if(year<=0) throw invalid();
    }
    private static void text(String value,int max,boolean empty) throws IOException {
        if(value==null || value.length()>max || (!empty && value.isBlank())) throw invalid();
        for(int i=0;i<value.length();i++) if(Character.isISOControl(value.charAt(i))) throw invalid();
    }
    private static String normalize(String mac) throws IOException {
        if(mac==null || !mac.matches("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}")) throw invalid();
        return mac.toUpperCase(Locale.ROOT);
    }
    private void write(byte[] payload) throws IOException {
        if(payload.length>LIMIT-44) throw invalid();
        if(!directory.isDirectory() && !directory.mkdirs()) throw new IOException("paired vehicle storage unavailable");
        File target=target(), temporary=File.createTempFile("paired-vehicle-",".tmp",directory);
        boolean published=false;
        try {
            try(FileOutputStream stream=new FileOutputStream(temporary); DataOutputStream out=new DataOutputStream(stream)) {
                out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(payload.length); out.write(hash(payload)); out.write(payload);
                out.flush(); stream.getFD().sync();
            }
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            published=true;
        } finally { if(!published) Files.deleteIfExists(temporary.toPath()); }
    }
    private static byte[] hash(byte[] bytes) throws IOException {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch(NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 unavailable"); }
    }
    private static IOException invalid() { return new IOException("invalid paired vehicle metadata"); }
}

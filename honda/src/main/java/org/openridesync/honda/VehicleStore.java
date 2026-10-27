package org.openridesync.honda;

import org.openridesync.honda.wire.HondaWire.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/** Private metadata only. Caller supplies a directory already scoped by verified UID SHA-256. */
public final class VehicleStore {
    private static final int MAGIC = 0x4f525653, VERSION = 1, LIMIT = 8 * 1024 * 1024;
    private final File directory;
    public VehicleStore(File directory) {
        if (directory == null) throw new IllegalArgumentException("directory");
        this.directory = directory;
    }
    public static final class Snapshot<T> {
        public final T value;
        public final long savedAtMillis;
        public final String countryCode;
        private Snapshot(T value, long savedAtMillis, String countryCode) {
            this.value=value; this.savedAtMillis=savedAtMillis; this.countryCode=countryCode;
        }
    }
    public static final class Selection {
        public final Model model;
        public final int year;
        private Selection(Model model, int year) { this.model=model; this.year=year; }
    }
    public Snapshot<ListModelCatalogsResponse> readCatalog(boolean ownCountry) throws IOException {
        Entry entry = read("catalog\n"+ownCountry);
        if (entry == null) return null;
        ListModelCatalogsResponse value = ListModelCatalogsResponse.parseFrom(entry.protobuf);
        validateCatalog(value);
        return new Snapshot<>(value,entry.saved,entry.country);
    }
    public void writeCatalog(boolean ownCountry, String country, ListModelCatalogsResponse value) throws IOException {
        validateCatalog(value); write("catalog\n"+ownCountry,country,0,bytes(value));
    }
    public Snapshot<ListModelSeriesResponse> readSeries(String label, boolean ownCountry, String country) throws IOException {
        Entry entry = read(seriesKey(label,ownCountry,country));
        if (entry == null) return null;
        if (!entry.country.equals(country)) throw new IOException("country mismatch");
        ListModelSeriesResponse value = ListModelSeriesResponse.parseFrom(entry.protobuf);
        validateSeries(value,label);
        return new Snapshot<>(value,entry.saved,entry.country);
    }
    public void writeSeries(String label, boolean ownCountry, String country, ListModelSeriesResponse value) throws IOException {
        String key = seriesKey(label,ownCountry,country);
        validateSeries(value,label); write(key,country,0,bytes(value));
    }
    public Selection readSelection() throws IOException {
        Entry entry = read("selection");
        if (entry == null) return null;
        Model value = Model.parseFrom(entry.protobuf); selection(value,entry.year);
        return new Selection(value,entry.year);
    }
    public void writeSelection(Model model, int year) throws IOException {
        selection(model,year); write("selection","-",year,bytes(model));
    }
    private static byte[] bytes(com.google.protobuf.MessageLite value) throws IOException {
        if (value.getSerializedSize()>LIMIT-44) throw new IOException("cache exceeds size limit");
        return value.toByteArray();
    }
    private static String seriesKey(String label, boolean ownCountry, String country) throws IOException {
        text(label,512); text(country,128);
        return "series\n"+ownCountry+"\n"+country+"\n"+label;
    }
    private static void text(String text, int max) throws IOException {
        if (text == null || text.isBlank() || text.length()>max) throw new IOException("invalid metadata identity");
        for (int i=0;i<text.length();i++) if (Character.isISOControl(text.charAt(i))) throw new IOException("invalid metadata identity");
    }
    private static void model(Model value) throws IOException {
        if (value == null) throw new IOException("missing model");
        text(value.getModelLabel(),512); text(value.getModelSeriesLabel(),512);
        for (int year : value.getModelYearsList()) if (year<=0) throw new IOException("invalid metadata year");
    }
    private static void selection(Model value, int year) throws IOException {
        model(value);
        if (year == 0 ? value.getModelYearsCount()!=0 : !value.getModelYearsList().contains(year)) throw new IOException("year absent from model metadata");
    }
    public static void validateCatalog(ListModelCatalogsResponse value) throws IOException {
        if (value == null) throw new IOException("missing catalog");
        Set<String> labels = new HashSet<>();
        Set<String> seriesLabels = new HashSet<>();
        for (ModelCatalog entry : value.getModelCatalogsList()) {
            if (!entry.hasModel() || entry.getModelCount()<0) throw new IOException("invalid catalog entry");
            model(entry.getModel());
            if (!labels.add(entry.getModel().getModelLabel()) || !seriesLabels.add(entry.getModel().getModelSeriesLabel())) throw new IOException("duplicate catalog model or series");
        }
    }
    public static void validateSeries(ListModelSeriesResponse value, String label) throws IOException {
        if (value == null) throw new IOException("missing series");
        Set<String> labels = new HashSet<>();
        for (ModelSeries group : value.getModelSeriesListList()) for (Model entry : group.getModelsList()) {
            model(entry);
            if (!entry.getModelSeriesLabel().equals(label) || !labels.add(entry.getModelLabel())) throw new IOException("invalid or duplicate series model");
        }
    }
    private File file(String key) throws IOException {
        byte[] digest = hash(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (byte b : digest) name.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        File file = new File(directory,name+".bin");
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("symlink cache entry");
        return file;
    }
    private static byte[] hash(byte[] data) throws IOException {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 unavailable",impossible); }
    }
    private static final class Entry {
        String country; long saved; int year; byte[] protobuf;
    }
    private Entry read(String key) throws IOException {
        File file=file(key);
        if (!file.exists()) return null;
        if (!file.isFile() || file.length()>LIMIT) throw new IOException("invalid cache size");
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            if (input.readInt()!=MAGIC || input.readInt()!=VERSION) throw new IOException("unsupported cache format");
            int length=input.readInt();
            if (length<0 || length>LIMIT-44 || file.length()!=length+44L) throw new IOException("cache length mismatch");
            byte[] digest=new byte[32], data=new byte[length]; input.readFully(digest); input.readFully(data);
            if (input.read()!=-1 || !MessageDigest.isEqual(digest,hash(data))) throw new IOException("cache digest mismatch");
            try (DataInputStream payload = new DataInputStream(new ByteArrayInputStream(data))) {
                if (!payload.readUTF().equals(key)) throw new IOException("cache identity mismatch");
                Entry entry=new Entry(); entry.country=payload.readUTF(); text(entry.country,128);
                entry.saved=payload.readLong(); entry.year=payload.readInt();
                int bytes=payload.readInt();
                if (entry.saved<=0 || bytes<0 || bytes!=payload.available()) throw new IOException("invalid cache payload");
                entry.protobuf=new byte[bytes]; payload.readFully(entry.protobuf); return entry;
            }
        }
    }
    private void write(String key, String country, int year, byte[] protobuf) throws IOException {
        text(country,128);
        if (protobuf.length>LIMIT-44) throw new IOException("cache exceeds size limit");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try (DataOutputStream payload=new DataOutputStream(bytes)) {
            payload.writeUTF(key); payload.writeUTF(country); payload.writeLong(System.currentTimeMillis());
            payload.writeInt(year); payload.writeInt(protobuf.length); payload.write(protobuf);
        }
        byte[] data=bytes.toByteArray();
        if (data.length>LIMIT-44) throw new IOException("cache exceeds size limit");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("cache directory unavailable");
        File target=file(key), temporary=File.createTempFile("vehicle-",".tmp",directory);
        try {
            try (FileOutputStream stream=new FileOutputStream(temporary); DataOutputStream output=new DataOutputStream(stream)) {
                output.writeInt(MAGIC); output.writeInt(VERSION); output.writeInt(data.length); output.write(hash(data)); output.write(data);
                output.flush(); stream.getFD().sync();
            }
            // No non-atomic fallback: unsupported filesystems fail while preserving old evidence.
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
}

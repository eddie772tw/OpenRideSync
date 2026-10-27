package org.openridesync.honda;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import org.openridesync.honda.wire.HondaWire.*;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;

/** Synthetic private-file checks only; no account, API, support or vehicle assertion. */
public final class VehicleStoreCheck {
    private interface Check { void run() throws Exception; }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void rejects(Check action) throws Exception {
        try { action.run(); throw new AssertionError("invalid cache operation accepted"); }
        catch (IOException expected) { }
    }
    private static File file(File directory, String key) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder(); for(byte b:digest) hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return new File(directory,hex+".bin");
    }
    public static void main(String[] args) throws Exception {
        File directory=Files.createTempDirectory("vehicle-store-check-").toFile();
        VehicleStore store=new VehicleStore(directory);
        require(store.readCatalog(true)==null && store.readSelection()==null,"missing metadata");
        UnknownFieldSet unknown=UnknownFieldSet.newBuilder().addField(100,UnknownFieldSet.Field.newBuilder().addVarint(77).build()).build();
        Model first=Model.newBuilder().setModelLabel("model-a").setModelSeriesLabel("series-a").addModelYears(2025)
            .setMeter(Meter.newBuilder().setType(99).setGeneration(123)).setUnknownFields(unknown)
            .addVehicleCodes(VehicleCode.newBuilder().setSeriesCode("shared-code")).build();
        Model second=first.toBuilder().setModelLabel("model-b").setModelSeriesLabel("series-b").build();
        ListModelCatalogsResponse catalog=ListModelCatalogsResponse.newBuilder().setUnknownFields(unknown)
            .addModelCatalogs(ModelCatalog.newBuilder().setModel(first).setModelCount(1))
            .addModelCatalogs(ModelCatalog.newBuilder().setModel(second).setModelCount(1)).build();
        store.writeCatalog(true,"TW",catalog);
        VehicleStore.Snapshot<ListModelCatalogsResponse> snapshot=store.readCatalog(true);
        require(Arrays.equals(catalog.toByteArray(),snapshot.value.toByteArray()),"catalog unknown fields preserved");
        require(snapshot.savedAtMillis>0 && snapshot.countryCode.equals("TW"),"snapshot metadata");
        require(snapshot.value.getModelCatalogs(0).getModel().getMeter().getGeneration()==123,"raw meter retained");
        ListModelSeriesResponse a=ListModelSeriesResponse.newBuilder().addModelSeriesList(ModelSeries.newBuilder().addModels(first)).setUnknownFields(unknown).build();
        ListModelSeriesResponse b=ListModelSeriesResponse.newBuilder().addModelSeriesList(ModelSeries.newBuilder().addModels(second)).build();
        store.writeSeries("series-a",true,"TW",a); store.writeSeries("series-b",true,"TW",b);
        require(Arrays.equals(a.toByteArray(),store.readSeries("series-a",true,"TW").value.toByteArray()),"series unknown fields retained");
        require(store.readSeries("series-b",true,"TW").value.getModelSeriesList(0).getModels(0).getModelLabel().equals("model-b"),"colliding seriesCode remains separate by label");
        require(store.readSeries("series-a",false,"TW")==null && store.readSeries("series-a",true,"JP")==null,"request scope isolation");
        String opaqueSeries="../family\\edition/.", opaqueLabel="../opaque\\model/..";
        Model opaque=first.toBuilder().setModelLabel(opaqueLabel).setModelSeriesLabel(opaqueSeries).build();
        ListModelSeriesResponse opaqueResponse=ListModelSeriesResponse.newBuilder().setUnknownFields(unknown)
            .addModelSeriesList(ModelSeries.newBuilder().addModels(opaque)).build();
        store.writeSeries(opaqueSeries,true,"TW",opaqueResponse);
        require(store.readSeries(opaqueSeries,true,"TW").value.equals(opaqueResponse),"slash and dot opaque series roundtrip");
        require(file(directory,"series\ntrue\nTW\n"+opaqueSeries).isFile(),"opaque label maps to hashed filename");
        ListModelCatalogsResponse opaqueCatalog=ListModelCatalogsResponse.newBuilder()
            .addModelCatalogs(ModelCatalog.newBuilder().setModel(opaque).setModelCount(1)).build();
        store.writeCatalog(false,"TW",opaqueCatalog);
        require(store.readCatalog(false).value.equals(opaqueCatalog),"opaque catalog model labels preserved");
        store.writeSelection(opaque,2025);
        require(store.readSelection().model.equals(opaque) && store.readSelection().year==2025,"opaque selection roundtrip");
        store.writeSelection(first,2025);
        require(store.readSelection().year==2025 && store.readSelection().model.equals(first),"selection preserves model and year");
        rejects(() -> store.writeSelection(first,0)); rejects(() -> store.writeSelection(first,2026));
        require(store.readSelection().year==2025,"invalid year preserves old selection");
        store.writeSelection(second.toBuilder().clearModelYears().build(),0);
        require(store.readSelection().year==0,"unknown year only for missing year metadata");
        File catalogFile=file(directory,"catalog\ntrue"); byte[] prior=Files.readAllBytes(catalogFile.toPath());
        rejects(() -> store.writeCatalog(true,"TW",catalog.toBuilder().addModelCatalogs(catalog.getModelCatalogs(0)).build()));
        require(Arrays.equals(prior,Files.readAllBytes(catalogFile.toPath())),"duplicate rejection preserves snapshot");
        Model repeatedSeries=first.toBuilder().setModelLabel("model-c").build();
        rejects(() -> store.writeCatalog(true,"TW",catalog.toBuilder()
            .addModelCatalogs(ModelCatalog.newBuilder().setModel(repeatedSeries).setModelCount(1)).build()));
        require(Arrays.equals(prior,Files.readAllBytes(catalogFile.toPath())),"duplicate series label with distinct model ID preserves snapshot");
        UnknownFieldSet huge=UnknownFieldSet.newBuilder().addField(101,UnknownFieldSet.Field.newBuilder()
            .addLengthDelimited(ByteString.copyFrom(new byte[8*1024*1024])).build()).build();
        rejects(() -> store.writeCatalog(true,"TW",catalog.toBuilder().mergeUnknownFields(huge).build()));
        require(Arrays.equals(prior,Files.readAllBytes(catalogFile.toPath())),"size rejection preserves snapshot");
        rejects(() -> store.writeSeries("series\nescape",true,"TW",a));
        rejects(() -> store.readSeries("series-a",true,"TW\t"));
        rejects(() -> store.writeCatalog(true,"",catalog));
        rejects(() -> store.writeSeries("series-a",true,"TW",a.toBuilder().addModelSeriesList(a.getModelSeriesList(0)).build()));
        byte[] corrupt=prior.clone(); corrupt[corrupt.length-1]^=1; Files.write(catalogFile.toPath(),corrupt);
        rejects(() -> store.readCatalog(true));
        Files.write(catalogFile.toPath(),Arrays.copyOf(prior,prior.length-1)); rejects(() -> store.readCatalog(true));
        corrupt=prior.clone(); corrupt[7]=2; Files.write(catalogFile.toPath(),corrupt); rejects(() -> store.readCatalog(true));
        Files.write(catalogFile.toPath(),prior);
        require(store.readCatalog(true).value.equals(catalog),"previous snapshot still readable");
        for(File entry:directory.listFiles()) {
            require(entry.getName().matches("[0-9a-f]{64}\\.bin"),"only hashed filenames; no raw identifiers or temp leftovers");
            require(entry.delete(),"cleanup own generated file");
        }
        require(directory.delete(),"cleanup own generated directory");
        System.out.println("PASS VehicleStore synthetic unknown fields, raw meters, scoped labels, years, corruption and failure preservation");
    }
}

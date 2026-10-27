package org.openridesync.honda;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import org.openridesync.honda.wire.HondaWire.*;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;

/** Raw synthetic snapshots only; neither live service validation nor cloud completeness. */
public final class TripCacheCheck {
    private interface Check { void run() throws Exception; }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void rejects(Check action) throws Exception {
        try { action.run(); throw new AssertionError("invalid trip cache accepted"); }
        catch (IOException expected) { }
    }
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("trip-cache-check-").toFile();
        File a = new File(root,"account-a"), b = new File(root,"account-b");
        TripCache cache = new TripCache(a), other = new TripCache(b);
        ListDailyTripsRequest request = ListDailyTripsRequest.newBuilder().setStartDate(1700000000)
            .setOffsetFromUtc(28800).setTripsCount(100).build();
        UnknownFieldSet unknown = UnknownFieldSet.newBuilder().addField(100, UnknownFieldSet.Field.newBuilder().addVarint(77).build()).build();
        ListDailyTripsResponse response = ListDailyTripsResponse.newBuilder().setUnknownFields(unknown)
            .addTrips(DailyTrips.newBuilder().setTripDate(1699920000).addTripUuids("../opaque/trip")
                .setTotalTime(3600).setUnknownFields(unknown)).build();
        require(cache.readDaily(request) == null, "empty cache");
        cache.writeDaily(request, response); cache.indexLatestDaily(request);
        require(other.readDaily(request) == null, "account isolation");
        TripCache restarted = new TripCache(a);
        require(restarted.latestDailyRequest(request.toBuilder().setStartDate(1800000000).build()).equals(request), "head recovers original request after restart");
        require(restarted.readDaily(request).savedAtMillis() > 0, "snapshot timestamp");
        require(Arrays.equals(response.toByteArray(), restarted.readDaily(request).value().toByteArray()), "raw unknown fields retained");
        require(cache.readDaily(request.toBuilder().setOffsetFromUtc(-18000).build()) == null, "offset isolation");
        require(cache.latestDailyRequest(request.toBuilder().setTripsCount(1).build()) == null, "summary/page isolation");
        require(cache.readDaily(request.toBuilder().setStartDate(1699000000).build()) == null, "cursor isolation");
        ListTripDetailsRequest detailRequest = ListTripDetailsRequest.newBuilder().addTripUuids("../opaque/trip").build();
        ListTripDetailsResponse detail = ListTripDetailsResponse.newBuilder().addDailyTripDetails(TripDetail.newBuilder()
            .setTripUuid("../opaque/trip").addTripLocations(TripLocation.newBuilder().setTimestamp(1)
                .setCoordinates(Coord.newBuilder().setLat(25).setLon(121)).setIsCoarse(true)).setUnknownFields(unknown))
            .setUnknownFields(unknown).build();
        cache.writeDetails(detailRequest, detail);
        require(cache.readDetails(detailRequest).value().equals(detail), "coordinates/coarse/unknown retention");
        require(cache.readDetails(ListTripDetailsRequest.newBuilder().addTripUuids("other").build()) == null, "detail identity isolation");
        UnknownFieldSet oversized = UnknownFieldSet.newBuilder().addField(101, UnknownFieldSet.Field.newBuilder()
            .addLengthDelimited(ByteString.copyFrom(new byte[8 * 1024 * 1024])).build()).build();
        rejects(() -> cache.writeDaily(request, response.toBuilder().setUnknownFields(oversized).build()));
        require(cache.readDaily(request).value().equals(response), "failed replacement preserves known good snapshot");
        File daily = Arrays.stream(a.listFiles()).filter(f -> f.getName().startsWith("daily-")).findFirst().orElseThrow();
        byte[] correct = Files.readAllBytes(daily.toPath());
        byte[] corrupt = correct.clone(); corrupt[corrupt.length-1] ^= 1;
        Files.write(daily.toPath(), corrupt); rejects(() -> cache.readDaily(request));
        Files.write(daily.toPath(), correct);
        cache.writeDaily(request.toBuilder().setStartDate(1699000000).build(), response);
        File alternate = Arrays.stream(a.listFiles()).filter(f -> f.getName().startsWith("daily-") && !f.equals(daily)).findFirst().orElseThrow();
        Files.copy(daily.toPath(), alternate.toPath(), StandardCopyOption.REPLACE_EXISTING);
        rejects(() -> cache.readDaily(request.toBuilder().setStartDate(1699000000).build()));
        // Sparse synthetic old entries exercise aggregate eviction without allocating a large protobuf.
        for (int i=0;i<9;i++) try (RandomAccessFile f = new RandomAccessFile(new File(a,"old-"+i+".bin"),"rw")) { f.setLength(8L*1024*1024); }
        cache.writeDetails(detailRequest, detail);
        long total = Arrays.stream(a.listFiles()).filter(f -> f.getName().endsWith(".bin")).mapToLong(File::length).sum();
        require(total <= 64L*1024*1024 && cache.readDetails(detailRequest).value().equals(detail), "bounded cache preserves new snapshot");
        System.out.println("PASS trip cache raw protobuf, request/UID isolation, head recovery, corruption, failed replacement and capacity");
    }
}

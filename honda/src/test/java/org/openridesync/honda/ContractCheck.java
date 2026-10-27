package org.openridesync.honda;

import com.google.protobuf.InvalidProtocolBufferException;
import org.openridesync.honda.wire.LegacyWire;
import org.openridesync.honda.wire.WeatherWire;
import org.openridesync.honda.wire.PipelineWire;
import org.openridesync.honda.wire.HondaWire.*;
import java.util.HexFormat;

/** Offline synthetic wire checks: no account, network, or device. */
public final class ContractCheck {
    private static byte[] bytes(String hex) { return HexFormat.of().parseHex(hex); }
    private static void equal(byte[] actual, String expected) {
        if (!HexFormat.of().formatHex(actual).equals(expected)) throw new AssertionError("Wire bytes differ");
    }
    public static void main(String[] args) throws Exception {
        // Legacy upload uses milliseconds, optional zero floats and signed zig-zag offset seconds.
        var point = PipelineWire.Location.newBuilder().setUuid("p").setUserId("u").setTripUuid("t")
            .setAppSessionId("s").setTime(1000).setLatitude(0).setLongitude(0).setSpeed(1.5f)
            .setDeviceTime(2000).setAppVersionCode(1).setPlatform("android")
            .setOffsetFromUtc(-3600).setIsCoarseLocation(true).build();
        equal(point.toByteArray(), "0a01701201751a017422017328e80735000000003d000000004d0000c03f8801d00fa80101ba0107616e64726f6964c0019f38c80101");
        var batch = PipelineWire.CreateLocationRequest.newBuilder().addLocations(point).build();
        HondaReadClient.validateLocations(batch);
        if (!PipelineWire.CreateLocationRequest.parseFrom(batch.toByteArray()).equals(batch)
            || !point.hasLatitude() || !point.hasLongitude() || !point.hasOffsetFromUtc()
            || point.hasAccuracy() || PipelineWire.CreateLocationResponse.getDefaultInstance().getSerializedSize() != 0)
            throw new AssertionError("Pipeline presence/request/response contract");
        for (var invalid : new PipelineWire.CreateLocationRequest[] {
                PipelineWire.CreateLocationRequest.getDefaultInstance(),
                batch.toBuilder().addLocations(point).build(),
                batch.toBuilder().setLocations(0, point.toBuilder().setUserId("")).build(),
                batch.toBuilder().setLocations(0, point.toBuilder().clearLatitude()).build(),
                batch.toBuilder().setLocations(0, point.toBuilder().setLatitude(Float.NaN)).build(),
                batch.toBuilder().setLocations(0, point.toBuilder().setTime(-1)).build(),
                batch.toBuilder().setLocations(0, point.toBuilder().setPlatform("other")).build() }) {
            try { HondaReadClient.validateLocations(invalid); throw new AssertionError("Invalid Pipeline batch accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        // Fixed32 coordinates and field4 repeated strings distinguish this legacy API from v2.
        var coordinate = WeatherWire.Coord.newBuilder().setLat(1).setLon(2).build();
        HondaReadClient.validateWeatherCoord(true, coordinate);
        HondaReadClient.validateWeatherCoord(true, WeatherWire.Coord.getDefaultInstance()); // Zero is a valid coordinate.
        for (var invalid : new float[][]{{Float.NaN, 0}, {0, Float.POSITIVE_INFINITY}, {91, 0}, {0, -181}}) {
            try {
                HondaReadClient.validateWeatherCoord(true, WeatherWire.Coord.newBuilder().setLat(invalid[0]).setLon(invalid[1]).build());
                throw new AssertionError("Invalid geographic coordinate accepted");
            } catch (IllegalArgumentException expected) { }
        }
        try { HondaReadClient.validateWeatherCoord(false, coordinate); throw new AssertionError("Missing coordinate accepted"); }
        catch (IllegalArgumentException expected) { }
        equal(WeatherWire.GetWeatherRequest.newBuilder().setCoord(coordinate).setLang("zh_tw").build().toByteArray(),
            "0a0a0d0000803f150000004012057a685f7477");
        equal(WeatherWire.GetWeatherForecastRequest.newBuilder().setCoord(coordinate).setLang("en")
            .addIntervals("0").addIntervals("3").build().toByteArray(),
            "0a0a0d0000803f15000000401202656e220130220133");
        var weather = WeatherWire.Weather.parseFrom(bytes("2a050d0000c84150077a0178"));
        if (!weather.hasMain() || weather.getMain().getTemp() != 25 || weather.getDt() != 7
            || !weather.getUnknownFields().hasField(15)) throw new AssertionError("Weather float/int/unknown contract");
        equal(weather.toByteArray(), "2a050d0000c84150077a0178");
        // Forecast.dt, City.timezone and Sys.sunrise are floats despite timestamp-like names.
        if (WeatherWire.Forecast.parseFrom(bytes("0d0000803f")).getDt() != 1
            || WeatherWire.City.parseFrom(bytes("3500000040")).getTimezone() != 2
            || WeatherWire.Sys.parseFrom(bytes("2d00004040")).getSunrise() != 3)
            throw new AssertionError("Legacy forecast fixed32 contract");
        try { WeatherWire.Weather.parseFrom(bytes("2a050d00")); throw new AssertionError("Truncated weather accepted"); }
        catch (InvalidProtocolBufferException expected) { }
        equal(ListModelCatalogsRequest.newBuilder().addMeterGenerations(1).addMeterGenerations(2).build().toByteArray(), "0a020102");
        equal(ListModelsRequest.newBuilder().addMeterGenerations(1).addMeterGenerations(2).addModelLabels("MO-16").build().toByteArray(), "0a02010212054d4f2d3136");
        equal(ListModelSeriesRequest.newBuilder().addMeterGenerations(1).addMeterGenerations(2).addModelSeriesLabels("MS-16").build().toByteArray(), "0a02010212054d532d3136");
        var models = ListModelsResponse.parseFrom(bytes("0a070a054d4f2d3136"));
        if (!models.getModels(0).getModelLabel().equals("MO-16")) throw new AssertionError("Model response");
        var unknown = Model.parseFrom(bytes("0a054d4f2d31367801"));
        if (!unknown.getUnknownFields().hasField(15)) throw new AssertionError("Unknown field lost");
        equal(unknown.toByteArray(), "0a054d4f2d31367801");
        // Unknown meter values remain raw numbers and are never mapped to a supported generation.
        if (Meter.parseFrom(bytes("08631063")).getGeneration() != 99) throw new AssertionError("Unknown generation");
        equal(GetUserRequest.getDefaultInstance().toByteArray(), "");
        equal(LegacyWire.GetUserRequest.getDefaultInstance().toByteArray(), "");
        var originalUser = LegacyWire.User.parseFrom(bytes("0a017512025457"));
        if (!originalUser.getUserId().equals("u") || !originalUser.getCountryCode().equals("TW")) throw new AssertionError("Original account response");
        var user = User.parseFrom(bytes("0a0175120254571801"));
        if (!user.getUserId().equals("u") || !user.getCountryCode().equals("TW") || !user.getCountryChanged()) throw new AssertionError("Account response");
        // proto3 optional sint32: negative UTC offset must ZigZag encode, and explicit zero retains presence.
        equal(ListDailyTripsRequest.newBuilder().setOffsetFromUtc(-480).setStartDate(1).setTripsCount(20).build().toByteArray(), "08bf0710011814");
        equal(ListDailyTripsRequest.newBuilder().setOffsetFromUtc(0).build().toByteArray(), "0800");
        if (ListDailyTripsRequest.getDefaultInstance().hasOffsetFromUtc()) throw new AssertionError("Offset presence");
        equal(GetTripDetailRequest.newBuilder().setTripId("t").build().toByteArray(), "0a0174");
        var detail = TripDetail.parseFrom(bytes("0a0174250000803f2d00000040"));
        if (detail.getAvgSpeed() != 1f || detail.getTotalDistance() != 2f) throw new AssertionError("Float trip metrics");
        // Pinned messageInfo: time type id 3 is UINT64, unlike FLOAT distance (id 1).
        var summary = TripsSummary.parseFrom(bytes("0d0000c03f10901c1807"));
        if (summary.getTotalDistance() != 1.5f || summary.getTotalTime() != 3600L || summary.getTotalDriveDay() != 7)
            throw new AssertionError("Summary integer seconds and float metres");
        equal(summary.toByteArray(), "0d0000c03f10901c1807");
        var unsignedTime = TripsSummary.parseFrom(bytes("10ffffffffffffffffff01"));
        if (!Long.toUnsignedString(unsignedTime.getTotalTime()).equals("18446744073709551615")) throw new AssertionError("uint64 retention");
        equal(unsignedTime.toByteArray(), "10ffffffffffffffffff01");
        var countries = ListAvailableCountriesResponse.parseFrom(bytes("0a040a02545712024a50"));
        if (!countries.getCountries(0).getCode().equals("TW") || !countries.getUnchangeableCountries(0).equals("JP")) throw new AssertionError("Country repeated types");
        if (TermsUpdate.parseFrom(bytes("2063")).getReason() != 99) throw new AssertionError("Unknown legal reason");
        // Remote REA: model_label=1, full BCP47 language_code=2, VERSION_2 enum number=2 at field 3.
        equal(GetModelSetupPageUrlRequest.newBuilder().setModelLabel("MO-16").setLanguageCode("zh-Hant-TW").setVersion(2)
            .build().toByteArray(), "0a054d4f2d3136120a7a682d48616e742d54571802");
        equal(GetModelTutorialPageUrlRequest.newBuilder().setModelLabel("MO-16").setLanguageCode("en-US").setVersion(2)
            .build().toByteArray(), "0a054d4f2d31361205656e2d55531802");
        if (!ModelSetupPageUrl.parseFrom(bytes("0a0169120175")).getId().equals("i") ||
            !ModelTutorialPageUrl.parseFrom(bytes("0a0169120175")).getUrl().equals("u")) throw new AssertionError("Guide response fields");
        var futureGuide = ModelSetupPageUrl.parseFrom(bytes("0a01691201757801"));
        if (!futureGuide.getUnknownFields().hasField(15)) throw new AssertionError("Guide unknown fields lost");
        equal(futureGuide.toByteArray(), "0a01691201757801");
        try { User.parseFrom(bytes("80")); throw new AssertionError("Truncated wire accepted"); }
        catch (InvalidProtocolBufferException expected) { }
        System.out.println("PASS Honda synthetic account/model/country/legal/trip wire contracts");
    }
}

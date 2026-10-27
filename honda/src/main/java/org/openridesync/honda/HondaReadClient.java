package org.openridesync.honda;

import com.google.protobuf.Message;
import io.grpc.*;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import org.openridesync.honda.wire.HondaWire.*;
import org.openridesync.honda.wire.LegacyWire;
import org.openridesync.honda.wire.WeatherWire;
import org.openridesync.honda.wire.PipelineWire;
import java.util.concurrent.TimeUnit;

/** Blocking original-account and v2 read RPCs. Call off the Android main thread. No automatic retry or account creation. */
public final class HondaReadClient implements AutoCloseable {
    public static final String HOST = "api.honda-roadsync.com";
    public static final String SERVICE = "com.honda.roadsync.api.v2.Service";
    private final ManagedChannel channel;

    /** TLS and normal platform certificate validation. No arbitrary endpoint or plaintext option. */
    public HondaReadClient(int androidSdk) {
        if (androidSdk < 30) throw new IllegalArgumentException("Android SDK must be at least 30");
        channel = OkHttpChannelBuilder.forAddress(HOST, 443).useTransportSecurity()
            .userAgent("RoadSync/26.8.10;309 android/" + androidSdk)
            .maxInboundMessageSize(8 * 1024 * 1024).disableRetry().build();
    }

    /** Credentials are supplied per call after obtaining a current original-project Firebase ID token.
     * They must never be placed in diagnostics; this class does not persist or stringify them. */
    private <Q extends Message, S extends Message> S read(String method, Q request, S response,
                                                         String apiKey, String idToken) {
        return readService(SERVICE, method, request, response, apiKey, idToken);
    }

    private <Q extends Message, S extends Message> S readService(String service, String method, Q request, S response,
                                                               String apiKey, String idToken) {
        validateHeader(apiKey);
        validateHeader(idToken);
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER), apiKey);
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + idToken);
        var descriptor = MethodDescriptor.<Q, S>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(service, method))
            .setRequestMarshaller(ProtoUtils.marshaller(request))
            .setResponseMarshaller(ProtoUtils.marshaller(response)).build();
        try {
            return ClientCalls.blockingUnaryCall(
                ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers)),
                descriptor, CallOptions.DEFAULT.withDeadlineAfter(15, TimeUnit.SECONDS), request);
        } catch (StatusRuntimeException failure) {
            // Remote descriptions/trailers can contain account data. Expose only the canonical status.
            throw Status.fromCode(failure.getStatus().getCode()).asRuntimeException();
        }
    }

    private static void validateHeader(String value) {
        if (value == null || value.isEmpty() || value.length() > 8192
                || value.chars().anyMatch(c -> c < 0x21 || c > 0x7e))
            throw new IllegalArgumentException("Invalid credential header");
    }

    /** Original account read used by OEM UserApi; no implicit login or account initialization. */
    public LegacyWire.User getUser(String apiKey, String idToken) {
        return readService("drivemode.api.v1.ApiService", "GetUser", LegacyWire.GetUserRequest.getDefaultInstance(),
            LegacyWire.User.getDefaultInstance(), apiKey, idToken);
    }

    /** One captured batch of real, account-owned ride samples. No automatic RPC retry.
     * A normal empty response permits local acknowledgement, not a claim of history visibility. */
    public void createLocations(PipelineWire.CreateLocationRequest request, String apiKey, String idToken) {
        validateLocations(request);
        readService("drivemode.api.v1.PipelineService", "CreateLocation", request,
            PipelineWire.CreateLocationResponse.getDefaultInstance(), apiKey, idToken);
    }

    static void validateLocations(PipelineWire.CreateLocationRequest request) {
        if (request.getLocationsCount() < 1 || request.getLocationsCount() > 100)
            throw new IllegalArgumentException("Expected one batch of up to 100 locations");
        String uid = request.getLocations(0).getUserId();
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (var point : request.getLocationsList()) {
            if (uid.isBlank() || !point.getUserId().equals(uid) || point.getUuid().isBlank()
                    || !ids.add(point.getUuid()) || point.getTripUuid().isBlank() || point.getAppSessionId().isBlank()
                    || point.getTime() <= 0 || point.getDeviceTime() <= 0
                    || !point.hasLatitude() || !point.hasLongitude()
                    || !Float.isFinite(point.getLatitude()) || !Float.isFinite(point.getLongitude())
                    || Math.abs(point.getLatitude()) > 90 || Math.abs(point.getLongitude()) > 180
                    || !point.getPlatform().equals("android"))
                throw new IllegalArgumentException("Invalid account-owned location batch");
        }
    }

    /** Original weather read; temperature is interpreted as Celsius by the OEM client.
     * Raw protobuf/unknown fields are retained. Payload presence is not a valid forecast gate. */
    public WeatherWire.Weather getWeather(WeatherWire.GetWeatherRequest request, String apiKey, String idToken) {
        validateWeatherCoord(request.hasCoord(), request.getCoord());
        return readService("drivemode.api.v1.ApiService", "GetWeather", request,
            WeatherWire.Weather.getDefaultInstance(), apiKey, idToken);
    }

    public WeatherWire.WeatherForecast getWeatherForecast(WeatherWire.GetWeatherForecastRequest request,
                                                          String apiKey, String idToken) {
        validateWeatherCoord(request.hasCoord(), request.getCoord());
        return readService("drivemode.api.v1.ApiService", "GetWeatherForecast", request,
            WeatherWire.WeatherForecast.getDefaultInstance(), apiKey, idToken);
    }

    static void validateWeatherCoord(boolean present, WeatherWire.Coord coord) {
        if (!present || !Float.isFinite(coord.getLat()) || !Float.isFinite(coord.getLon())
            || Math.abs(coord.getLat()) > 90 || Math.abs(coord.getLon()) > 180)
            throw new IllegalArgumentException("Weather requires a finite geographic coordinate");
    }

    public User getV2User(GetUserRequest request, String apiKey, String idToken) {
        return read("GetUser", request, User.getDefaultInstance(), apiKey, idToken);
    }

    public ListAvailableCountriesResponse listAvailableCountries(ListAvailableCountriesRequest request, String apiKey, String idToken) {
        return read("ListAvailableCountries", request, ListAvailableCountriesResponse.getDefaultInstance(), apiKey, idToken);
    }

    public Country getCountryOfResidence(GetCountryOfResidenceRequest request, String apiKey, String idToken) {
        return read("GetCountryOfResidence", request, Country.getDefaultInstance(), apiKey, idToken);
    }

    public TermsUpdate getTermsUpdate(GetTermsUpdateRequest request, String apiKey, String idToken) {
        return read("GetTermsUpdate", request, TermsUpdate.getDefaultInstance(), apiKey, idToken);
    }

    public PrivacyPolicyUpdate getPrivacyPolicyUpdate(GetPrivacyPolicyUpdateRequest request, String apiKey, String idToken) {
        return read("GetPrivacyPolicyUpdate", request, PrivacyPolicyUpdate.getDefaultInstance(), apiKey, idToken);
    }

    public ListModelCatalogsResponse listModelCatalogs(ListModelCatalogsRequest request, String apiKey, String idToken) {
        return read("ListModelCatalogs", request, ListModelCatalogsResponse.getDefaultInstance(), apiKey, idToken);
    }

    public ListModelSeriesResponse listModelSeries(ListModelSeriesRequest request, String apiKey, String idToken) {
        return read("ListModelSeries", request, ListModelSeriesResponse.getDefaultInstance(), apiKey, idToken);
    }

    public ListModelsResponse listModels(ListModelsRequest request, String apiKey, String idToken) {
        return read("ListModels", request, ListModelsResponse.getDefaultInstance(), apiKey, idToken);
    }

    public ImageBinary getImageBinary(GetImageBinaryRequest request, String apiKey, String idToken) {
        return read("GetImageBinary", request, ImageBinary.getDefaultInstance(), apiKey, idToken);
    }

    public ModelSetupPageUrl getModelSetupPageUrl(GetModelSetupPageUrlRequest request, String apiKey, String idToken) {
        return read("GetModelSetupPageUrl", request, ModelSetupPageUrl.getDefaultInstance(), apiKey, idToken);
    }

    public ModelTutorialPageUrl getModelTutorialPageUrl(GetModelTutorialPageUrlRequest request, String apiKey, String idToken) {
        return read("GetModelTutorialPageUrl", request, ModelTutorialPageUrl.getDefaultInstance(), apiKey, idToken);
    }

    public ListDailyTripsResponse listDailyTrips(ListDailyTripsRequest request, String apiKey, String idToken) {
        return read("ListDailyTrips", request, ListDailyTripsResponse.getDefaultInstance(), apiKey, idToken);
    }

    public ListTripDetailsResponse listTripDetails(ListTripDetailsRequest request, String apiKey, String idToken) {
        return read("ListTripDetails", request, ListTripDetailsResponse.getDefaultInstance(), apiKey, idToken);
    }

    public TripDetail getTripDetail(GetTripDetailRequest request, String apiKey, String idToken) {
        return read("GetTripDetail", request, TripDetail.getDefaultInstance(), apiKey, idToken);
    }

    @Override public void close() { channel.shutdownNow(); }
}

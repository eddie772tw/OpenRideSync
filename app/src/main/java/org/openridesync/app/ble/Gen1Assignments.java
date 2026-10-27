package org.openridesync.app.ble;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pinned OEM assignment wire codec; transport, retries and vehicle policy belong to the caller. */
public final class Gen1Assignments {
    private Gen1Assignments() {}

    private static final int[][] COLORS = {
        {214,71,71}, {56,168,67}, {48,154,135}, {36,115,101}, {11,39,34},
        {120,70,163}, {87,50,122}, {39,22,55}, {69,122,177}, {52,92,134},
        {18,31,44}, {216,132,43}, {170,105,39}, {76,40,10},
        {113,113,113}, {75,75,75}, {39,39,39}
    };

    /** OEM VehicleInformation.l: equality of series values compares only seriesCode. */
    public static boolean colorRegistrationNeeded(int vehicleType, String seriesCode) {
        if (seriesCode == null) throw invalid();
        return vehicleType != 0 && !List.of("03F5", "0365", "03BC", "0401", "0434",
            "0448", "04B6", "03B5", "04B5", "052B").contains(seriesCode);
    }

    /** firstRequestId is the first ID to send, wrapping at 65535. No global ID state. */
    public static List<byte[]> frames(int firstRequestId, boolean includeColors) {
        if (firstRequestId < 0 || firstRequestId > 65535) throw invalid();
        List<byte[]> frames = new ArrayList<>();
        int id = firstRequestId;
        frames.add(frame(1, id++, 5, 1, 7, 128, 0));
        frames.add(frame(1, id++, 6, 1, 7, 129, 0));
        for (int function : new int[]{1,2,3,4,7,8,9,10}) frames.add(frame(1, id++, function, 2));
        if (includeColors) {
            for (int index = 0; index < COLORS.length; index++) {
                int[] rgb = COLORS[index];
                frames.add(frame(2, id++, index + 2, rgb[0], rgb[1], rgb[2]));
            }
        }
        return List.copyOf(frames);
    }

    /** No CRC, padding or request ID is attached to the OEM end-control write. */
    public static byte[] endFrame() { return new byte[]{3}; }

    public record Result(int command, int requestId, int status, Integer errorPosition) {
        public boolean accepted() { return status == 1 || status == 13; }
    }

    public static Result decodeResult(byte[] bytes) {
        if (bytes == null || bytes.length < 4 || bytes.length > 5) throw invalid();
        int command = bytes[0] & 255;
        int status = bytes[3] & 255;
        if (command < 1 || command > 3 || !(status == 0 || status == 1 || status == 11 || status == 12 || status == 13)) throw invalid();
        return new Result(command, (bytes[1] & 255) | ((bytes[2] & 255) << 8), status,
            bytes.length == 5 ? bytes[4] & 255 : null);
    }

    /** OEM correlation uses the two ID bytes and excludes an exact request echo. */
    public static boolean matches(byte[] request, byte[] response) {
        return request != null && response != null && request.length >= 3 && response.length >= 3
            && request[1] == response[1] && request[2] == response[2] && !Arrays.equals(request, response);
    }

    private static byte[] frame(int command, int requestId, int... payload) {
        byte[] result = new byte[payload.length + 3];
        result[0] = (byte) command;
        result[1] = (byte) requestId;
        result[2] = (byte) (requestId >>> 8);
        for (int index = 0; index < payload.length; index++) result[index + 3] = (byte) payload[index];
        return result;
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid Gen1 assignment data"); }
}

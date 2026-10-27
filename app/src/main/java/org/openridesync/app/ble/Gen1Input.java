package org.openridesync.app.ble;

import java.util.UUID;

/** Inbound Gen1 wire values only; decoding never invokes a vehicle or application action. */
public final class Gen1Input {
    private Gen1Input() {}
    public static final UUID SERVICE = UUID.fromString("8536e103-6771-4d4b-9702-287cb5e1340f");
    public static final UUID FUNCTION_SOURCE = UUID.fromString("6c060578-d1d9-460a-b86f-eb97f01b2227");
    public static final UUID DRIVING_INFO = UUID.fromString("bb1d5836-bf35-11ed-afa1-0242ac120002");

    public enum Function {
        UNKNOWN, PREVIOUS, NEXT, BACK, SELECT, VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE, MENU, MODE_SAB, MODE_HU
    }
    public enum DataType { FAST_DATA, FAST_DATA_AND_SLOW_DATA, UNKNOWN }

    public static final class FunctionEvent {
        public final Function function;
        public final int rawId;
        private final byte[] raw;
        private FunctionEvent(byte[] value) {
            raw = value.clone();
            rawId = raw[0] & 255;
            function = rawId >= 1 && rawId <= 10 ? Function.values()[rawId] : Function.UNKNOWN;
        }
        public byte[] raw() { return raw.clone(); }
        public int trailingByteCount() { return raw.length - 1; }
    }

    public static final class DrivingInfo {
        public final DataType dataType;
        /** Null means absent; unknown type bytes are retained rather than inferred. */
        public final Integer rawType;
        /** Unsigned OEM soc1 byte. No percent/range/unit conversion is established here. */
        public final Integer soc1;
        /** Client error flag only; missing offset 33 stays unknown, with no unit inference. */
        public final Boolean isError;
        public final boolean expectedLength;
        private final byte[] raw;
        private DrivingInfo(byte[] value) {
            raw = value.clone();
            expectedLength = raw.length == 47;
            rawType = raw.length > 0 ? raw[0] & 255 : null;
            dataType = rawType == null ? DataType.UNKNOWN : switch (rawType) {
                case 0 -> DataType.FAST_DATA;
                case 1 -> DataType.FAST_DATA_AND_SLOW_DATA;
                default -> DataType.UNKNOWN;
            };
            soc1 = raw.length > 33 ? raw[33] & 255 : null;
            isError = soc1 == null ? null : soc1 == 101;
        }
        public byte[] raw() { return raw.clone(); }
    }

    /** OEM examines byte zero and ignores trailing bytes. Unknown IDs remain non-actionable. */
    public static FunctionEvent decodeFunction(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw invalid();
        return new FunctionEvent(bytes);
    }

    /** OEM accepts any length; absent fields remain explicit instead of fabricating zero telemetry. */
    public static DrivingInfo decodeDrivingInfo(byte[] bytes) {
        if (bytes == null) throw invalid();
        return new DrivingInfo(bytes);
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid Gen1 input data"); }
}

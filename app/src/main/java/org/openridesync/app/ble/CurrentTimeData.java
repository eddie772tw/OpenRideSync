package org.openridesync.app.ble;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Objects;

/** Deterministic encoding of the pinned OEM CTS payload, including its rounding quirks. */
public final class CurrentTimeData {
    private CurrentTimeData() {}

    public enum AdjustReason {
        UNKNOWN(0), MANUAL(1), EXTERNAL_REFERENCE(2), TIME_ZONE_CHANGE(4), DAYLIGHT_SAVING(8);
        public final int value;
        AdjustReason(int value) { this.value = value; }
    }

    public static byte[] currentTime(LocalDateTime time, AdjustReason reason) {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(reason, "reason");
        // OEM uses whole milliseconds * 0.255f, not the spec's 256 fractions/second.
        return ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) time.getYear()).put((byte) time.getMonthValue())
                .put((byte) time.getDayOfMonth()).put((byte) time.getHour())
                .put((byte) time.getMinute()).put((byte) time.getSecond())
                .put((byte) time.getDayOfWeek().getValue())
                .put((byte) ((time.getNano() / 1_000_000L) * 0.255f))
                .put((byte) reason.value).array();
    }

    public static byte[] localTimeInformation(ZonedDateTime time) {
        Objects.requireNonNull(time, "time");
        // OEM subtracts the same current offset from itself: DST is always zero.
        return new byte[] {(byte) (time.getOffset().getTotalSeconds() / 60 / 15), 0};
    }
}

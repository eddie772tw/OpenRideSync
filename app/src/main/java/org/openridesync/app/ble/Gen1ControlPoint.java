package org.openridesync.app.ble;

import java.util.Arrays;

/** ControlPoint wire only. Page payloads remain in org.openridesync.gen1.ScreenEncoder. */
public final class Gen1ControlPoint {
    private Gen1ControlPoint() {}

    public static final int PHONE = 0x01, NAVIGATION = 0x02, MUSIC = 0x04,
        MESSAGE = 0x08, MUTE = 0x10, NO_NETWORK = 0x20;

    /** RS-FRAME-004: preserve the signed mask's leading byte, even for unnamed bits. */
    public static byte[] activeIndicators(int requestId, int mask) {
        if (requestId < 0 || requestId > 255 || mask < 0 || mask > 255) throw invalid();
        return new byte[]{3, (byte) requestId, (byte) ((mask & 0x80) == 0 ? 0 : 0xff), (byte) mask};
    }

    public static byte[] requestSabMode(int requestId) {
        if (requestId < 0 || requestId > 255) throw invalid();
        return new byte[]{10, (byte) requestId};
    }

    /** Status is deliberately raw: OEM parsing accepts every status byte. */
    public record Receipt(int command, int requestId, int status, Integer errorPosition) {}

    public static Receipt decodeReceipt(byte[] bytes) {
        if (bytes == null || bytes.length < 3 || bytes.length > 4) throw invalid();
        int command = bytes[0] & 255;
        if (!(command == 1 || command == 2 || command == 3 || command == 10 || command == 11)) throw invalid();
        return new Receipt(command, bytes[1] & 255, bytes[2] & 255,
            bytes.length == 4 ? bytes[3] & 255 : null);
    }

    /** OEM matches ID byte only, excluding an exact command echo; parse separately. */
    public static boolean matches(byte[] command, byte[] receipt) {
        return command != null && receipt != null && command.length >= 2 && receipt.length >= 2
            && command[1] == receipt[1] && !Arrays.equals(command, receipt);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Gen1 ControlPoint data");
    }
}

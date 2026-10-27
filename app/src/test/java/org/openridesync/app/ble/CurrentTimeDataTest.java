package org.openridesync.app.ble;

import org.junit.Test;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import static org.junit.Assert.assertArrayEquals;

public final class CurrentTimeDataTest {
    @Test public void pinnedOemPayloads() {
        LocalDateTime leap = LocalDateTime.of(2024, 2, 29, 23, 59, 58, 999_999_999);
        assertArrayEquals(new byte[] {(byte)0xe8,7,2,29,23,59,58,4,(byte)254,4},
                CurrentTimeData.currentTime(leap, CurrentTimeData.AdjustReason.TIME_ZONE_CHANGE));
        for (CurrentTimeData.AdjustReason reason : CurrentTimeData.AdjustReason.values()) {
            assertArrayEquals(new byte[] {(byte)0xe9,7,1,5,0,0,0,7,127,(byte)reason.value},
                    CurrentTimeData.currentTime(LocalDateTime.of(2025,1,5,0,0,0,500_000_000), reason));
        }
        assertArrayEquals(new byte[] {23,0}, CurrentTimeData.localTimeInformation(leap.atZone(ZoneId.of("Asia/Kathmandu"))));
        assertArrayEquals(new byte[] {-20,0}, CurrentTimeData.localTimeInformation(LocalDateTime.of(2025,1,1,0,0).atZone(ZoneId.of("America/New_York"))));
        assertArrayEquals(new byte[] {-16,0}, CurrentTimeData.localTimeInformation(LocalDateTime.of(2025,7,1,0,0).atZone(ZoneId.of("America/New_York"))));
        assertArrayEquals(new byte[] {-14,0}, CurrentTimeData.localTimeInformation(leap.atOffset(ZoneOffset.ofHoursMinutes(-3,-30)).toZonedDateTime()));
    }
}

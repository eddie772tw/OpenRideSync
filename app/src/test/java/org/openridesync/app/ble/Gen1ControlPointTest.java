package org.openridesync.app.ble;

import org.junit.Test;
import static org.junit.Assert.*;

public class Gen1ControlPointTest {
    @Test public void modeIsTwoBytesWithoutChecksum() {
        assertArrayEquals(new byte[]{10,0}, Gen1ControlPoint.requestSabMode(0));
        assertArrayEquals(new byte[]{10,-1}, Gen1ControlPoint.requestSabMode(255));
        assertThrows(IllegalArgumentException.class, () -> Gen1ControlPoint.requestSabMode(256));
    }
    @Test public void rawReceiptStatusAndOptionalPosition() {
        var receipt = Gen1ControlPoint.decodeReceipt(new byte[]{10,-1,12,-128});
        assertEquals(10, receipt.command());
        assertEquals(255, receipt.requestId());
        assertEquals(12, receipt.status());
        assertEquals(Integer.valueOf(128), receipt.errorPosition());
        assertNull(Gen1ControlPoint.decodeReceipt(new byte[]{1,1,1}).errorPosition());
        assertEquals(255, Gen1ControlPoint.decodeReceipt(new byte[]{11,1,-1}).status());
        assertThrows(IllegalArgumentException.class, () -> Gen1ControlPoint.decodeReceipt(new byte[]{4,1,1}));
        assertThrows(IllegalArgumentException.class, () -> Gen1ControlPoint.decodeReceipt(new byte[]{10,1}));
        assertThrows(IllegalArgumentException.class, () -> Gen1ControlPoint.decodeReceipt(new byte[]{10,1,1,0,0}));
    }
    @Test public void correlationUsesIdAndExcludesEcho() {
        byte[] command = Gen1ControlPoint.requestSabMode(7);
        assertFalse(Gen1ControlPoint.matches(command, command));
        assertFalse(Gen1ControlPoint.matches(command, new byte[]{10,8,1}));
        assertTrue(Gen1ControlPoint.matches(command, new byte[]{10,7,12}));
        // This intentionally reproduces OEM correlation; parsing does not add a command-match gate.
        assertTrue(Gen1ControlPoint.matches(command, new byte[]{2,7,1}));
    }
}

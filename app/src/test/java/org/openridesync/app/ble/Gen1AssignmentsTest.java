package org.openridesync.app.ble;

import org.junit.Test;
import static org.junit.Assert.*;

public class Gen1AssignmentsTest {
    @Test public void pinnedFramesAndWrap() {
        var frames = Gen1Assignments.frames(65535, true);
        assertEquals(27, frames.size());
        assertArrayEquals(new byte[]{1,-1,-1,5,1,7,-128,0}, frames.get(0));
        assertArrayEquals(new byte[]{1,0,0,6,1,7,-127,0}, frames.get(1));
        assertArrayEquals(new byte[]{1,1,0,1,2}, frames.get(2));
        assertArrayEquals(new byte[]{2,9,0,2,-42,71,71}, frames.get(10));
        assertArrayEquals(new byte[]{2,25,0,18,39,39,39}, frames.get(26));
        assertEquals(10, Gen1Assignments.frames(1, false).size());
        assertArrayEquals(new byte[]{3}, Gen1Assignments.endFrame());
    }
    @Test public void receiptValidationAndCorrelation() {
        byte[] request = Gen1Assignments.frames(0x1234, false).get(0);
        byte[] response = {1,0x34,0x12,13};
        assertTrue(Gen1Assignments.matches(request,response));
        assertFalse(Gen1Assignments.matches(request,request));
        assertEquals(0x1234, Gen1Assignments.decodeResult(response).requestId());
        assertTrue(Gen1Assignments.decodeResult(response).accepted());
        assertFalse(Gen1Assignments.decodeResult(new byte[]{2,0,0,12,3}).accepted());
        assertEquals(Integer.valueOf(3), Gen1Assignments.decodeResult(new byte[]{2,0,0,12,3}).errorPosition());
        assertThrows(IllegalArgumentException.class, () -> Gen1Assignments.decodeResult(new byte[]{1,0,0,2}));
        assertThrows(IllegalArgumentException.class, () -> Gen1Assignments.decodeResult(new byte[]{1,0,0}));
    }
    @Test public void vehiclePolicyIsNotDisplayCapability() {
        assertFalse(Gen1Assignments.colorRegistrationNeeded(0, "FFFF"));
        assertFalse(Gen1Assignments.colorRegistrationNeeded(1, "0434"));
        assertFalse(Gen1Assignments.colorRegistrationNeeded(1, "03F5"));
        assertTrue(Gen1Assignments.colorRegistrationNeeded(1, "FFFF"));
        assertTrue(Gen1Assignments.colorRegistrationNeeded(255, "FFFF"));
    }
}

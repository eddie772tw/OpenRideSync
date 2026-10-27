package org.openridesync.app.ble;

import org.junit.Test;
import static org.junit.Assert.*;

public final class Gen1InputTest {
    @Test public void pinnedFunctionsAndUnknownsDoNotInventActions() {
        String[] names = {"PREVIOUS","NEXT","BACK","SELECT","VOLUME_UP","VOLUME_DOWN","VOLUME_MUTE","MENU","MODE_SAB","MODE_HU"};
        for (int id = 1; id <= 10; id++) {
            byte[] wire = {(byte) id, (byte) 0xa5};
            Gen1Input.FunctionEvent event = Gen1Input.decodeFunction(wire);
            assertEquals(names[id - 1], event.function.name());
            assertEquals(id, event.rawId);
            assertEquals(1, event.trailingByteCount());
            wire[0] = 0;
            byte[] copy = event.raw(); copy[0] = 0;
            assertEquals(id, event.raw()[0]);
        }
        for (int id : new int[]{0,11,127,128,255}) {
            Gen1Input.FunctionEvent event = Gen1Input.decodeFunction(new byte[]{(byte) id});
            assertEquals(Gen1Input.Function.UNKNOWN, event.function);
            assertEquals(id, event.rawId);
        }
        assertThrows(IllegalArgumentException.class, () -> Gen1Input.decodeFunction(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> Gen1Input.decodeFunction(null));
    }

    @Test public void drivingInfoPreservesOpaqueBytesAndMissingFields() {
        byte[] wire = new byte[47];
        for (int index = 0; index < wire.length; index++) wire[index] = (byte)(index + 130);
        wire[0] = 1; wire[33] = (byte)255;
        Gen1Input.DrivingInfo info = Gen1Input.decodeDrivingInfo(wire);
        assertTrue(info.expectedLength);
        assertEquals(Gen1Input.DataType.FAST_DATA_AND_SLOW_DATA, info.dataType);
        assertEquals(Integer.valueOf(255), info.soc1);
        assertEquals(Boolean.FALSE, info.isError);
        assertArrayEquals(wire, info.raw());
        wire[33] = 0; info.raw()[0] = 0;
        assertEquals(1, info.raw()[0]); assertEquals(255, info.raw()[33] & 255);
        Gen1Input.DrivingInfo empty = Gen1Input.decodeDrivingInfo(new byte[0]);
        assertNull(empty.rawType); assertNull(empty.soc1); assertNull(empty.isError); assertFalse(empty.expectedLength);
        assertEquals(Gen1Input.DataType.UNKNOWN, empty.dataType);
        assertEquals(Gen1Input.DataType.FAST_DATA, Gen1Input.decodeDrivingInfo(new byte[]{0}).dataType);
        assertNull(Gen1Input.decodeDrivingInfo(new byte[33]).soc1);
        assertEquals(Integer.valueOf(0), Gen1Input.decodeDrivingInfo(new byte[34]).soc1);
        Gen1Input.DrivingInfo future = Gen1Input.decodeDrivingInfo(new byte[]{(byte)254});
        assertEquals(Integer.valueOf(254), future.rawType);
        assertEquals(Gen1Input.DataType.UNKNOWN, future.dataType);
        assertFalse(Gen1Input.decodeDrivingInfo(new byte[48]).expectedLength);
        byte[] error = new byte[47]; error[33] = 101;
        assertEquals(Boolean.TRUE, Gen1Input.decodeDrivingInfo(error).isError);
    }
}

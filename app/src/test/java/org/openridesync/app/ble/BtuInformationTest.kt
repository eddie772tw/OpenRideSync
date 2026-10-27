package org.openridesync.app.ble

import org.junit.Assert.*
import org.junit.Test

class BtuInformationTest {
    @Test fun seriesCodeReversesNumericByteOrder() {
        assertEquals("1234", BtuInformation.decodeSeriesCode(byteArrayOf(0x34, 0x12)))
        assertEquals("800000FF", BtuInformation.decodeSeriesCode(byteArrayOf(-1, 0, 0, -128)))
        assertThrows(IllegalArgumentException::class.java) { BtuInformation.decodeSeriesCode(ByteArray(3)) }
    }
    @Test fun firmwareRetainsOemSignedByteFormatting() {
        assertEquals("01.09", BtuInformation.decodeFirmwareVersion(byteArrayOf(1, 9)))
        assertEquals("-1.-128", BtuInformation.decodeFirmwareVersion(byteArrayOf(-1, -128)))
        assertThrows(IllegalArgumentException::class.java) { BtuInformation.decodeFirmwareVersion(ByteArray(1)) }
    }
    @Test fun textAndUnknownBitsAreNotNormalized() {
        assertEquals("A\u0000", BtuInformation.decodeText(byteArrayOf(65, 0)))
        assertEquals(255, BtuInformation.decodeFirstByte(byteArrayOf(-1)))
        assertThrows(IllegalArgumentException::class.java) { BtuInformation.decodeFirstByte(ByteArray(0)) }
    }
}

package org.openridesync.app.ble

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the Android crypto provider with synthetic vectors; never connects to Bluetooth. */
@RunWith(AndroidJUnit4::class)
class Gen1SecurityDeviceTest {
    @Test fun platformProviderMatchesGen1Vectors() {
        val key = ByteArray(16) { it.toByte() }
        fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(hex("0100db4bc948f1988c0b82d74d332f9f2275"), Gen1Security.validationRequest(key))
        assertEquals(0x12345678, Gen1Security.validationResponse(key, hex("0500f85ce9978e0ea049852f08eaefd4367f")))
        assertEquals(-1, Gen1Security.validationResponse(key, hex("05005de009892e3cb126570e70561970f248")))
        val pair = Gen1Security.newExchangeKeyPair()
        val request = Gen1Security.exchangeRequest(pair.public as java.security.interfaces.RSAPublicKey)
        assertEquals(129, request.size)
        val plaintext = ByteArray(128)
        System.arraycopy(key, 0, plaintext, 110, key.size)
        // Independent CRC fixture for key 00..0f.
        val crc = hex("0a17")
        System.arraycopy(crc, 0, plaintext, 126, 2)
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, pair.public)
        assertArrayEquals(key, Gen1Security.exchangeResponse(pair.private, cipher.doFinal(plaintext)))
    }
}

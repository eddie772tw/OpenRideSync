package org.openridesync.app.ble;

import static org.junit.Assert.*;
import org.junit.Test;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;

public class Gen1SecurityTest {
    private static final byte[] KEY = hex("000102030405060708090a0b0c0d0e0f");

    @Test public void crcAndValidationMatchIndependentVectors() throws Exception {
        assertArrayEquals(hex("3dbb"), Gen1Security.crc("123456789".getBytes(StandardCharsets.US_ASCII)));
        assertArrayEquals(hex("4001"), Gen1Security.crc(new byte[]{3}));
        // Generated independently with Python cryptography AES/ECB, explicit PKCS7 bytes.
        assertArrayEquals(hex("0100db4bc948f1988c0b82d74d332f9f2275"), Gen1Security.validationRequest(KEY));
        assertEquals(0x12345678, Gen1Security.validationResponse(KEY, hex("0500f85ce9978e0ea049852f08eaefd4367f")));
        // Plaintext ends in zero, so PKCS padding fails and this exercises the NoPadding fallback.
        assertEquals(-1, Gen1Security.validationResponse(KEY, hex("05005de009892e3cb126570e70561970f248")));
        // The OEM does not interpret the two-byte response prefix.
        assertEquals(-1, Gen1Security.validationResponse(KEY, hex("ffff5de009892e3cb126570e70561970f248")));
    }

    @Test public void rsaExchangeChecksKeyCrcAndUnsignedModulus() throws Exception {
        KeyPair pair = Gen1Security.newExchangeKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
        byte[] request = Gen1Security.exchangeRequest(publicKey);
        assertEquals(129, request.length);
        assertEquals(2, request[0]);
        assertEquals(publicKey.getModulus(), new BigInteger(1, Arrays.copyOfRange(request, 1, request.length)));
        byte[] plain = new byte[128];
        System.arraycopy(KEY, 0, plain, 110, 16);
        System.arraycopy(Gen1Security.crc(KEY), 0, plain, 126, 2);
        // A synthetic peer using modular exponentiation, independent of the decoder's JCA path.
        assertArrayEquals(KEY, Gen1Security.exchangeResponse(pair.getPrivate(), rsaEncrypt(publicKey, plain)));
        plain[127] ^= 1;
        assertThrows(GeneralSecurityException.class,
                () -> Gen1Security.exchangeResponse(pair.getPrivate(), rsaEncrypt(publicKey, plain)));
        assertThrows(GeneralSecurityException.class, () -> Gen1Security.exchangeResponse(pair.getPrivate(), new byte[]{1}));
    }

    @Test public void rejectsMalformedOrRejectedValidationWithoutPayloadInError() throws Exception {
        assertFalse(Gen1Security.securedStatus(new byte[]{0}));
        assertTrue(Gen1Security.securedStatus(new byte[]{1}));
        for (byte[] status : new byte[][]{null, {}, {2}, {1, 0}})
            assertThrows(GeneralSecurityException.class, () -> Gen1Security.securedStatus(status));
        for (byte[] response : new byte[][]{null, {}, {1}, new byte[17], new byte[19], new byte[514], new byte[18]}) {
            GeneralSecurityException failure = assertThrows(GeneralSecurityException.class,
                    () -> Gen1Security.validationResponse(KEY, response));
            assertNotNull(failure.getMessage());
            assertFalse(failure.getMessage().contains(Arrays.toString(KEY)));
        }
        assertThrows(GeneralSecurityException.class, () -> Gen1Security.validationRequest(new byte[32]));
        // Independently encrypt a rejected status and a padded success without the full increment ID.
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(KEY, "AES"));
        for (byte[] plain : new byte[][]{{0, 1, 2, 3, 4}, {1, 2}}) {
            byte[] response = new byte[18];
            System.arraycopy(cipher.doFinal(plain), 0, response, 2, 16);
            assertThrows(GeneralSecurityException.class, () -> Gen1Security.validationResponse(KEY, response));
        }
    }

    private static byte[] rsaEncrypt(RSAPublicKey key, byte[] plain) {
        byte[] encoded = new BigInteger(1, plain).modPow(key.getPublicExponent(), key.getModulus()).toByteArray();
        byte[] result = new byte[128];
        int length = Math.min(128, encoded.length);
        System.arraycopy(encoded, encoded.length - length, result, 128 - length, length);
        return result;
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
}

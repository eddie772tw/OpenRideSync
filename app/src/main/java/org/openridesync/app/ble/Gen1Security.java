package org.openridesync.app.ble;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Arrays;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** OEM Gen1 wire compatibility only. See docs/research/app/OEM-PAIRING-SECURITY.md.
 * This legacy vehicle protocol is not a general purpose encryption scheme. */
public final class Gen1Security {
    private Gen1Security() {}

    /** Unknown status must not trigger a key-changing exchange in the replacement app. */
    public static boolean securedStatus(byte[] response) throws GeneralSecurityException {
        if (response == null || response.length != 1 || (response[0] != 0 && response[0] != 1)) throw invalid();
        return response[0] == 1;
    }

    public static KeyPair newExchangeKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(new RSAKeyGenParameterSpec(1024, RSAKeyGenParameterSpec.F4));
        return generator.generateKeyPair();
    }

    public static byte[] exchangeRequest(RSAPublicKey key) {
        if (key.getModulus().bitLength() != 1024 ||
                !key.getPublicExponent().equals(RSAKeyGenParameterSpec.F4))
            throw new IllegalArgumentException("Gen1 RSA parameters");
        byte[] modulus = key.getModulus().toByteArray();
        byte[] request = new byte[129];
        request[0] = 2;
        // The high bit of a 1024-bit modulus needs a sign byte in BigInteger, not on the wire.
        System.arraycopy(modulus, modulus.length - 128, request, 1, 128);
        return request;
    }

    /** Returns a new 16-byte key only after the vehicle's little-endian CRC matches. */
    public static byte[] exchangeResponse(PrivateKey privateKey, byte[] response)
            throws GeneralSecurityException {
        if (response == null || response.length != 128) throw invalid();
        Cipher rsa = Cipher.getInstance("RSA/ECB/NoPadding");
        rsa.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] plain = rsa.doFinal(response);
        byte[] key = null;
        try {
            if (plain.length != 128) throw invalid();
            key = Arrays.copyOfRange(plain, 110, 126);
            if (!MessageDigest.isEqual(crc(key), Arrays.copyOfRange(plain, 126, 128))) throw invalid();
            byte[] result = key;
            key = null;
            return result;
        } finally {
            Arrays.fill(plain, (byte) 0);
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    public static byte[] validationRequest(byte[] key) throws GeneralSecurityException {
        SecretKeySpec aes = aesKey(key);
        byte[] command = {3};
        byte[] plain = ByteBuffer.allocate(3).put(command).put(crc(command)).array();
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, aes);
        // JCA PKCS5Padding for AES has the same 16-byte padding as OEM PKCS7Padding.
        byte[] encrypted = cipher.doFinal(plain);
        return ByteBuffer.allocate(2 + encrypted.length).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 1).put(encrypted).array();
    }

    /** Preserves all 32 bits of the OEM little-endian increment ID, including its sign bit. */
    public static int validationResponse(byte[] key, byte[] response) throws GeneralSecurityException {
        SecretKeySpec aes = aesKey(key);
        if (response == null || response.length < 18 || response.length > 512 ||
                (response.length - 2) % 16 != 0) throw invalid();
        // OEM ignores these two outer bytes; there is no proven nonce or response CRC field.
        byte[] encrypted = Arrays.copyOfRange(response, 2, response.length);
        byte[] plain;
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, aes);
        try {
            plain = cipher.doFinal(encrypted);
        } catch (BadPaddingException badPadding) {
            // Some Gen1 responses are unpadded. Match the OEM compatibility path.
            cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aes);
            plain = cipher.doFinal(encrypted);
        }
        try {
            if (plain.length < 5 || plain[0] != 1) throw invalid();
            return ByteBuffer.wrap(plain, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        } finally { Arrays.fill(plain, (byte) 0); }
    }

    /** CRC-16/ARC, init=0, reflected 0xa001, xorout=0; transmitted low byte first. */
    static byte[] crc(byte[] data) {
        int crc = 0;
        for (byte value : data) {
            crc ^= value & 0xff;
            for (int bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) != 0 ? 0xa001 : 0);
        }
        return new byte[]{(byte) crc, (byte) (crc >>> 8)};
    }

    private static SecretKeySpec aesKey(byte[] key) throws GeneralSecurityException {
        if (key == null || key.length != 16) throw invalid();
        return new SecretKeySpec(key, "AES");
    }

    private static GeneralSecurityException invalid() {
        return new GeneralSecurityException("Invalid Gen1 security response");
    }
}

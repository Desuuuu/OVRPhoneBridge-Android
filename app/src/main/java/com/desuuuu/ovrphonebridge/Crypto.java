package com.desuuuu.ovrphonebridge;

import android.text.TextUtils;
import android.util.Base64;

import org.libsodium.jni.NaCl;
import org.libsodium.jni.Sodium;
import org.libsodium.jni.encoders.Encoder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class Crypto {
    private byte[] mKey;

    Crypto(String key) throws Exception {
        NaCl.sodium();

        mKey = Base64.decode(key, Base64.DEFAULT);

        if (mKey.length != Sodium.crypto_aead_xchacha20poly1305_ietf_keybytes()) {
            throw new Exception("Invalid key length");
        }
    }

    String encrypt(String plainText) throws Exception {
        NaCl.sodium();

        byte[] input = plainText.getBytes(StandardCharsets.UTF_8);

        ByteBuffer ad = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        ad.putLong(getCurrentTime());

        byte[] nonce = new byte[Sodium.crypto_aead_xchacha20poly1305_ietf_npubbytes()];
        Sodium.randombytes_buf(nonce, nonce.length);

        byte[] cipher = new byte[input.length + Sodium.crypto_aead_xchacha20poly1305_ietf_abytes()];
        int[] cipherLen = new int[1];

        if (Sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(
                cipher,
                cipherLen,
                input,
                input.length,
                ad.array(),
                ad.capacity(),
                new byte[0],
                nonce,
                mKey) != 0) {
            throw new Exception("Encryption failed");
        }

        ByteBuffer result = ByteBuffer.allocate(ad.capacity() + nonce.length + cipherLen[0]);

        result.put(ad.array());
        result.put(nonce);
        result.put(cipher, 0, cipherLen[0]);

        return Base64.encodeToString(result.array(), Base64.DEFAULT | Base64.NO_WRAP);
    }

    String decrypt(String encryptedText) throws Exception {
        NaCl.sodium();

        ByteBuffer data = ByteBuffer.wrap(Base64.decode(encryptedText, Base64.DEFAULT));

        ByteBuffer ad = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);

        byte[] nonce = new byte[Sodium.crypto_aead_xchacha20poly1305_ietf_npubbytes()];

        if (data.capacity() <= ad.capacity() + nonce.length + Sodium.crypto_aead_xchacha20poly1305_ietf_abytes()) {
            throw new Exception("Invalid input");
        }

        byte[] encrypted = new byte[data.capacity() - ad.capacity() - nonce.length];

        data.get(ad.array());
        data.get(nonce);
        data.get(encrypted);

        ByteBuffer decrypted = ByteBuffer.allocate(
                encrypted.length - Sodium.crypto_aead_xchacha20poly1305_ietf_abytes());

        int[] decryptedLen = new int[1];

        if (Sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(
                decrypted.array(),
                decryptedLen,
                new byte[0],
                encrypted,
                encrypted.length,
                ad.array(),
                ad.capacity(),
                nonce,
                mKey) != 0) {
            throw new Exception("Decryption failed");
        }

        long timestamp = ad.getLong();
        long currentTime = getCurrentTime();

        if (timestamp > currentTime + Constants.TIMESTAMP_LEEWAY
                || timestamp < currentTime - Constants.TIMESTAMP_LEEWAY) {
            throw new Exception("Expired message");
        }

        byte[] result = new byte[decryptedLen[0]];

        decrypted.get(result);

        return new String(result, StandardCharsets.UTF_8);
    }

    static byte[] getServerPublicKey(String publicKeyHex, String secretKeyHex, String data) throws Exception {
        NaCl.sodium();

        byte[] encrypted = Encoder.HEX.decode(data);

        ByteBuffer decrypted = ByteBuffer.allocate(
                encrypted.length - Sodium.crypto_box_sealbytes()).order(ByteOrder.BIG_ENDIAN);

        if (Sodium.crypto_box_seal_open(
                decrypted.array(),
                encrypted,
                encrypted.length,
                Encoder.HEX.decode(publicKeyHex),
                Encoder.HEX.decode(secretKeyHex)) != 0) {
            throw new Exception("Invalid message");
        }

        long timestamp = decrypted.getLong();
        long currentTime = getCurrentTime();

        if (timestamp > currentTime + Constants.TIMESTAMP_LEEWAY
                || timestamp < currentTime - Constants.TIMESTAMP_LEEWAY) {
            throw new Exception("Expired server public key");
        }

        byte[] serverPublicKey = new byte[decrypted.remaining()];

        decrypted.get(serverPublicKey);

        return serverPublicKey;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    static boolean generateKeyPair(SharedPreferences sharedPreferences) {
        NaCl.sodium();

        byte[] publicKey = new byte[32];
        byte[] secretKey = new byte[32];

        Sodium.crypto_kx_keypair(publicKey, secretKey);

        return sharedPreferences.edit()
                .putString("public_key", Encoder.HEX.encode(publicKey))
                .putString("secret_key", Encoder.HEX.encode(secretKey))
                .putString("identifier", getIdentifier(publicKey))
                .commit();
    }

    private static String getIdentifier(byte[] publicKey) {
        NaCl.sodium();

        byte[] key = {
            (byte)0x32, (byte)0x65, (byte)0x40, (byte)0x4d, (byte)0x1d, (byte)0x30, (byte)0x66, (byte)0x34,
            (byte)0x29, (byte)0x90, (byte)0xb8, (byte)0x91, (byte)0x8a, (byte)0x8f, (byte)0x5b, (byte)0xa1
        };

        byte[] shortHash = new byte[Sodium.crypto_shorthash_bytes()];

        Sodium.crypto_shorthash(shortHash, publicKey, publicKey.length, key);

        StringBuilder identifier = new StringBuilder(Encoder.HEX.encode(shortHash));

        if (identifier.length() % 2 != 0) {
            identifier.insert(0, '0');
        }

        int i = identifier.length() - 2;

        while (i > 0) {
            identifier.insert(i, ':');

            i -= 2;
        }

        return "[" + identifier.toString().toUpperCase() + "]";
    }

    private static long getCurrentTime() {
        return (System.currentTimeMillis() / 1000L);
    }
}

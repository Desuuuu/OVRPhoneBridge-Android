package com.desuuuu.ovrphonebridge;

import android.text.TextUtils;
import android.util.Base64;

import org.libsodium.jni.NaCl;
import org.libsodium.jni.Sodium;

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

    public static String derivePassword(String password) throws Exception {
        NaCl.sodium();

        if (TextUtils.isEmpty(password)
                || password.length() < Constants.ENCRYPTION_PASSWORD_MIN_LENGTH) {
            throw new PasswordTooShortException(Constants.ENCRYPTION_PASSWORD_MIN_LENGTH);
        }

        byte[] input = password.getBytes(StandardCharsets.UTF_8);

        byte[] salt = new byte[16];

        transformSalt(
                salt,
                salt.length,
                Constants.ENCRYPTION_PASSWORD_SALT.getBytes(StandardCharsets.UTF_8),
                Constants.ENCRYPTION_PASSWORD_SALT.length());

        byte[] key = new byte[Sodium.crypto_aead_xchacha20poly1305_ietf_keybytes()];

        if (Sodium.crypto_pwhash(
                key,
                key.length,
                input,
                input.length,
                salt,
                4,
                67108864,
                Sodium.crypto_pwhash_alg_argon2i13()) != 0) {
            throw new Exception("Hashing failed");
        }

        return Base64.encodeToString(key, Base64.DEFAULT | Base64.NO_WRAP);
    }

    private static long getCurrentTime() {
        return (System.currentTimeMillis() / 1000L);
    }

    private static void transformSalt(byte[] dest, int destLen, byte[] src, int srcLen) {
        if (srcLen > 0) {
            int i = 0;
            int j = 0;

            while (i < destLen) {
                dest[i++] = src[j++];

                if (j == srcLen) {
                    j = 0;
                }
            }
        }
    }

    public static class PasswordTooShortException extends Exception {
        private int mMinLen;

        PasswordTooShortException(int minLen) {
            super("Password too short");

            mMinLen = minLen;
        }

        public int getMinLength() {
            return mMinLen;
        }
    }
}

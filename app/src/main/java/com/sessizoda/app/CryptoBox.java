package com.sessizoda.app;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

final class CryptoBox {
    private static final int KEY_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec encryptionKey;

    CryptoBox(String roomCode, String sharedSecret) throws GeneralSecurityException {
        String normalizedRoom = normalizeRoom(roomCode);
        byte[] salt = sha256(("sessiz-oda-v1|" + normalizedRoom).getBytes(StandardCharsets.UTF_8));
        char[] password = sharedSecret.toCharArray();
        PBEKeySpec keySpec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS);
        try {
            byte[] keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(keySpec)
                    .getEncoded();
            encryptionKey = new SecretKeySpec(keyBytes, "AES");
            Arrays.fill(keyBytes, (byte) 0);
        } finally {
            Arrays.fill(password, '\0');
            keySpec.clearPassword();
            Arrays.fill(salt, (byte) 0);
        }
    }

    static String roomId(String roomCode) throws GeneralSecurityException {
        return toHex(sha256(("room|" + normalizeRoom(roomCode)).getBytes(StandardCharsets.UTF_8)));
    }

    String authProof(String roomId) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(encryptionKey.getEncoded(), "HmacSHA256"));
        return toHex(mac.doFinal(("auth|" + roomId).getBytes(StandardCharsets.UTF_8)));
    }

    String encrypt(String sender, String message) throws GeneralSecurityException {
        try {
            JSONObject clearText = new JSONObject();
            clearText.put("v", 1);
            clearText.put("sender", sender);
            clearText.put("message", message);

            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(clearText.toString().getBytes(StandardCharsets.UTF_8));
            byte[] packet = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, packet, 0, nonce.length);
            System.arraycopy(encrypted, 0, packet, nonce.length, encrypted.length);
            Arrays.fill(encrypted, (byte) 0);
            return Base64.encodeToString(packet, Base64.NO_WRAP);
        } catch (JSONException exception) {
            throw new GeneralSecurityException("Mesaj hazırlanamadı.", exception);
        }
    }

    DecryptedMessage decrypt(String payload) throws GeneralSecurityException {
        try {
            byte[] packet = Base64.decode(payload, Base64.NO_WRAP);
            if (packet.length <= NONCE_BYTES + 16) {
                throw new GeneralSecurityException("Şifreli mesaj geçersiz.");
            }
            byte[] nonce = Arrays.copyOfRange(packet, 0, NONCE_BYTES);
            byte[] encrypted = Arrays.copyOfRange(packet, NONCE_BYTES, packet.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] clearBytes = cipher.doFinal(encrypted);
            String clearJson = new String(clearBytes, StandardCharsets.UTF_8);
            Arrays.fill(clearBytes, (byte) 0);
            JSONObject clearText = new JSONObject(clearJson);
            if (clearText.optInt("v", 0) != 1) {
                throw new GeneralSecurityException("Mesaj sürümü desteklenmiyor.");
            }
            String sender = clearText.getString("sender");
            String message = clearText.getString("message");
            if (sender.trim().isEmpty() || sender.length() > 24 || message.trim().isEmpty() || message.length() > 2_000) {
                throw new GeneralSecurityException("Mesaj içeriği geçersiz.");
            }
            return new DecryptedMessage(sender, message);
        } catch (IllegalArgumentException | JSONException exception) {
            throw new GeneralSecurityException("Şifreli mesaj açılamadı.", exception);
        }
    }

    private static String normalizeRoom(String roomCode) {
        return Normalizer.normalize(roomCode.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private static byte[] sha256(byte[] input) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            builder.append(Character.forDigit(value & 0x0f, 16));
        }
        return builder.toString();
    }

    static final class DecryptedMessage {
        final String sender;
        final String message;

        DecryptedMessage(String sender, String message) {
            this.sender = sender;
            this.message = message;
        }
    }
}

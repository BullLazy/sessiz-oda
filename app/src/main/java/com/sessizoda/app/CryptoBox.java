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
    static final int MEDIA_CHUNK_BYTES = 12 * 1024;
    static final long MAX_IMAGE_BYTES = 100L * 1024L * 1024L;
    static final long MAX_VIDEO_BYTES = 500L * 1024L * 1024L;

    private static final int KEY_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MAX_MEDIA_CHUNKS = 50_000;
    private static final long MAX_CLOCK_DIFFERENCE_MS = 7L * 24L * 60L * 60L * 1_000L;
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

    String encryptText(String sender, String message) throws GeneralSecurityException {
        try {
            JSONObject clearText = new JSONObject();
            clearText.put("v", 1);
            clearText.put("sender", sender);
            clearText.put("message", message);
            clearText.put("sentAt", System.currentTimeMillis());
            return encryptObject(clearText);
        } catch (JSONException exception) {
            throw new GeneralSecurityException("Mesaj hazırlanamadı.", exception);
        }
    }

    String encryptMediaStart(
            String transferId,
            String sender,
            String mimeType,
            String displayName,
            long size
    ) throws GeneralSecurityException {
        try {
            JSONObject clearText = new JSONObject();
            clearText.put("v", 2);
            clearText.put("type", "media_start");
            clearText.put("id", transferId);
            clearText.put("sender", sender);
            clearText.put("mime", mimeType);
            clearText.put("name", displayName);
            clearText.put("size", size);
            clearText.put("sentAt", System.currentTimeMillis());
            return encryptObject(clearText);
        } catch (JSONException exception) {
            throw new GeneralSecurityException("Medya bilgisi hazırlanamadı.", exception);
        }
    }

    String encryptMediaChunk(String transferId, int index, byte[] data, int length)
            throws GeneralSecurityException {
        if (length <= 0 || length > MEDIA_CHUNK_BYTES || length > data.length) {
            throw new GeneralSecurityException("Medya parçası geçersiz.");
        }
        try {
            byte[] exactData = Arrays.copyOf(data, length);
            JSONObject clearText = new JSONObject();
            clearText.put("v", 2);
            clearText.put("type", "media_chunk");
            clearText.put("id", transferId);
            clearText.put("index", index);
            clearText.put("data", Base64.encodeToString(exactData, Base64.NO_WRAP));
            Arrays.fill(exactData, (byte) 0);
            return encryptObject(clearText);
        } catch (JSONException exception) {
            throw new GeneralSecurityException("Medya parçası hazırlanamadı.", exception);
        }
    }

    String encryptMediaEnd(String transferId, int chunks, String sha256)
            throws GeneralSecurityException {
        try {
            JSONObject clearText = new JSONObject();
            clearText.put("v", 2);
            clearText.put("type", "media_end");
            clearText.put("id", transferId);
            clearText.put("chunks", chunks);
            clearText.put("sha256", sha256);
            return encryptObject(clearText);
        } catch (JSONException exception) {
            throw new GeneralSecurityException("Medya sonu hazırlanamadı.", exception);
        }
    }

    DecryptedPacket decryptPacket(String payload) throws GeneralSecurityException {
        try {
            JSONObject clearText = decryptObject(payload);
            int version = clearText.optInt("v", 0);
            if (version == 1) {
                String sender = validateSender(clearText.getString("sender"));
                String message = clearText.getString("message");
                if (message.trim().isEmpty() || message.length() > 2_000) {
                    throw new GeneralSecurityException("Mesaj içeriği geçersiz.");
                }
                return DecryptedPacket.text(sender, message, readTimestamp(clearText));
            }
            if (version != 2) {
                throw new GeneralSecurityException("Mesaj sürümü desteklenmiyor.");
            }

            String type = clearText.optString("type", "");
            String transferId = validateTransferId(clearText.optString("id", ""));
            switch (type) {
                case "media_start": {
                    String sender = validateSender(clearText.getString("sender"));
                    String mimeType = clearText.getString("mime");
                    String name = clearText.getString("name");
                    long size = clearText.getLong("size");
                    long mediaLimit = mimeType.startsWith("image/")
                            ? MAX_IMAGE_BYTES
                            : MAX_VIDEO_BYTES;
                    if (
                            (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) ||
                            mimeType.length() > 80 ||
                            name.trim().isEmpty() ||
                            name.length() > 120 ||
                            size <= 0 ||
                            size > mediaLimit
                    ) {
                        throw new GeneralSecurityException("Medya bilgisi geçersiz.");
                    }
                    return DecryptedPacket.mediaStart(
                            transferId,
                            sender,
                            mimeType,
                            name,
                            size,
                            readTimestamp(clearText)
                    );
                }
                case "media_chunk": {
                    int index = clearText.getInt("index");
                    if (index < 0 || index >= MAX_MEDIA_CHUNKS) {
                        throw new GeneralSecurityException("Medya sırası geçersiz.");
                    }
                    byte[] data = Base64.decode(clearText.getString("data"), Base64.NO_WRAP);
                    if (data.length == 0 || data.length > MEDIA_CHUNK_BYTES) {
                        Arrays.fill(data, (byte) 0);
                        throw new GeneralSecurityException("Medya parçası geçersiz.");
                    }
                    return DecryptedPacket.mediaChunk(transferId, index, data);
                }
                case "media_end": {
                    int chunks = clearText.getInt("chunks");
                    String digest = clearText.getString("sha256");
                    if (chunks <= 0 || chunks > MAX_MEDIA_CHUNKS || !digest.matches("^[a-f0-9]{64}$")) {
                        throw new GeneralSecurityException("Medya doğrulaması geçersiz.");
                    }
                    return DecryptedPacket.mediaEnd(transferId, chunks, digest);
                }
                default:
                    throw new GeneralSecurityException("Mesaj türü desteklenmiyor.");
            }
        } catch (IllegalArgumentException | JSONException exception) {
            throw new GeneralSecurityException("Şifreli paket açılamadı.", exception);
        }
    }

    private String encryptObject(JSONObject clearText) throws GeneralSecurityException {
        byte[] clearBytes = clearText.toString().getBytes(StandardCharsets.UTF_8);
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(clearBytes);
            byte[] packet = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, packet, 0, nonce.length);
            System.arraycopy(encrypted, 0, packet, nonce.length, encrypted.length);
            Arrays.fill(encrypted, (byte) 0);
            String encoded = Base64.encodeToString(packet, Base64.NO_WRAP);
            Arrays.fill(packet, (byte) 0);
            return encoded;
        } finally {
            Arrays.fill(clearBytes, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
        }
    }

    private JSONObject decryptObject(String payload) throws GeneralSecurityException, JSONException {
        byte[] packet = Base64.decode(payload, Base64.NO_WRAP);
        if (packet.length <= NONCE_BYTES + 16) {
            Arrays.fill(packet, (byte) 0);
            throw new GeneralSecurityException("Şifreli paket geçersiz.");
        }
        byte[] nonce = Arrays.copyOfRange(packet, 0, NONCE_BYTES);
        byte[] encrypted = Arrays.copyOfRange(packet, NONCE_BYTES, packet.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] clearBytes = cipher.doFinal(encrypted);
            try {
                return new JSONObject(new String(clearBytes, StandardCharsets.UTF_8));
            } finally {
                Arrays.fill(clearBytes, (byte) 0);
            }
        } finally {
            Arrays.fill(packet, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(encrypted, (byte) 0);
        }
    }

    private static String validateSender(String sender) throws GeneralSecurityException {
        if (sender.trim().isEmpty() || sender.length() > 24) {
            throw new GeneralSecurityException("Gönderen geçersiz.");
        }
        return sender;
    }

    private static String validateTransferId(String transferId) throws GeneralSecurityException {
        if (!transferId.matches("^[a-f0-9]{32}$")) {
            throw new GeneralSecurityException("Medya kimliği geçersiz.");
        }
        return transferId;
    }

    private static long readTimestamp(JSONObject clearText) {
        long receivedAt = System.currentTimeMillis();
        long sentAt = clearText.optLong("sentAt", receivedAt);
        if (
                sentAt <= 0 ||
                sentAt < receivedAt - MAX_CLOCK_DIFFERENCE_MS ||
                sentAt > receivedAt + MAX_CLOCK_DIFFERENCE_MS
        ) {
            return receivedAt;
        }
        return sentAt;
    }

    private static String normalizeRoom(String roomCode) {
        return Normalizer.normalize(roomCode.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private static byte[] sha256(byte[] input) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }

    static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            builder.append(Character.forDigit(value & 0x0f, 16));
        }
        return builder.toString();
    }

    static final class DecryptedPacket {
        static final String TEXT = "text";
        static final String MEDIA_START = "media_start";
        static final String MEDIA_CHUNK = "media_chunk";
        static final String MEDIA_END = "media_end";

        final String type;
        final String transferId;
        final String sender;
        final String text;
        final String mimeType;
        final String displayName;
        final String digest;
        final long size;
        final long sentAt;
        final int index;
        final int chunks;
        final byte[] data;

        private DecryptedPacket(
                String type,
                String transferId,
                String sender,
                String text,
                String mimeType,
                String displayName,
                String digest,
                long size,
                long sentAt,
                int index,
                int chunks,
                byte[] data
        ) {
            this.type = type;
            this.transferId = transferId;
            this.sender = sender;
            this.text = text;
            this.mimeType = mimeType;
            this.displayName = displayName;
            this.digest = digest;
            this.size = size;
            this.sentAt = sentAt;
            this.index = index;
            this.chunks = chunks;
            this.data = data;
        }

        static DecryptedPacket text(String sender, String text, long sentAt) {
            return new DecryptedPacket(TEXT, null, sender, text, null, null, null, 0, sentAt, 0, 0, null);
        }

        static DecryptedPacket mediaStart(
                String id,
                String sender,
                String mime,
                String name,
                long size,
                long sentAt
        ) {
            return new DecryptedPacket(MEDIA_START, id, sender, null, mime, name, null, size, sentAt, 0, 0, null);
        }

        static DecryptedPacket mediaChunk(String id, int index, byte[] data) {
            return new DecryptedPacket(MEDIA_CHUNK, id, null, null, null, null, null, 0, 0, index, 0, data);
        }

        static DecryptedPacket mediaEnd(String id, int chunks, String digest) {
            return new DecryptedPacket(MEDIA_END, id, null, null, null, null, digest, 0, 0, 0, chunks, null);
        }
    }
}

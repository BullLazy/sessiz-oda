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

    String encryptText(
            String messageId,
            String deviceId,
            String sender,
            String message,
            long sentAt,
            ChatEvent.Reply reply
    ) throws GeneralSecurityException {
        try {
            JSONObject clearText = baseMessage("text", messageId, deviceId, sender, sentAt, reply);
            clearText.put("message", message);
            return encryptObject(clearText);
        } catch (JSONException exception) {
            throw new GeneralSecurityException("Mesaj hazırlanamadı.", exception);
        }
    }

    String encryptMediaStart(
            String transferId,
            String deviceId,
            String sender,
            String mimeType,
            String displayName,
            long size,
            long sentAt,
            boolean viewOnce,
            ChatEvent.Reply reply
    ) throws GeneralSecurityException {
        try {
            JSONObject clearText = baseMessage(
                    "media_start",
                    transferId,
                    deviceId,
                    sender,
                    sentAt,
                    reply
            );
            clearText.put("mime", mimeType);
            clearText.put("name", displayName);
            clearText.put("size", size);
            clearText.put("viewOnce", viewOnce);
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
        byte[] exactData = Arrays.copyOf(data, length);
        try {
            JSONObject clearText = new JSONObject();
            clearText.put("v", 2);
            clearText.put("type", "media_chunk");
            clearText.put("id", transferId);
            clearText.put("index", index);
            clearText.put("data", Base64.encodeToString(exactData, Base64.NO_WRAP));
            return encryptObject(clearText);
        } catch (JSONException exception) {
            throw new GeneralSecurityException("Medya parçası hazırlanamadı.", exception);
        } finally {
            Arrays.fill(exactData, (byte) 0);
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

    String encryptReceipt(String messageId, String deviceId, String state)
            throws GeneralSecurityException {
        if (!"delivered".equals(state) && !"seen".equals(state)) {
            throw new GeneralSecurityException("Teslim bilgisi geçersiz.");
        }
        try {
            JSONObject clearText = new JSONObject();
            clearText.put("v", 3);
            clearText.put("type", "receipt");
            clearText.put("id", validateMessageId(messageId));
            clearText.put("device", validateDeviceId(deviceId));
            clearText.put("state", state);
            return encryptObject(clearText);
        } catch (JSONException exception) {
            throw new GeneralSecurityException("Teslim bilgisi hazırlanamadı.", exception);
        }
    }

    DecryptedPacket decryptPacket(String payload) throws GeneralSecurityException {
        try {
            JSONObject clearText = decryptObject(payload);
            int version = clearText.optInt("v", 0);
            if (version == 1) {
                String sender = validateSender(clearText.getString("sender"));
                String message = validateMessage(clearText.getString("message"));
                return DecryptedPacket.text(
                        null,
                        null,
                        sender,
                        message,
                        readTimestamp(clearText),
                        null
                );
            }
            if (version == 2) {
                return decryptVersionTwo(clearText);
            }
            if (version != 3) {
                throw new GeneralSecurityException("Mesaj sürümü desteklenmiyor.");
            }

            String type = clearText.optString("type", "");
            String messageId = validateMessageId(clearText.optString("id", ""));
            String deviceId = validateDeviceId(clearText.optString("device", ""));
            if ("receipt".equals(type)) {
                String state = clearText.optString("state", "");
                if (!"delivered".equals(state) && !"seen".equals(state)) {
                    throw new GeneralSecurityException("Teslim bilgisi geçersiz.");
                }
                return DecryptedPacket.receipt(messageId, deviceId, state);
            }

            String sender = validateSender(clearText.getString("sender"));
            long sentAt = readTimestamp(clearText);
            ChatEvent.Reply reply = readReply(clearText);
            if ("text".equals(type)) {
                return DecryptedPacket.text(
                        messageId,
                        deviceId,
                        sender,
                        validateMessage(clearText.getString("message")),
                        sentAt,
                        reply
                );
            }
            if ("media_start".equals(type)) {
                String mimeType = clearText.getString("mime");
                String name = clearText.getString("name");
                long size = clearText.getLong("size");
                validateMedia(mimeType, name, size);
                return DecryptedPacket.mediaStart(
                        messageId,
                        deviceId,
                        sender,
                        mimeType,
                        name,
                        size,
                        sentAt,
                        clearText.optBoolean("viewOnce", false),
                        reply
                );
            }
            throw new GeneralSecurityException("Mesaj türü desteklenmiyor.");
        } catch (IllegalArgumentException | JSONException exception) {
            throw new GeneralSecurityException("Şifreli paket açılamadı.", exception);
        }
    }

    private DecryptedPacket decryptVersionTwo(JSONObject clearText)
            throws JSONException, GeneralSecurityException {
        String type = clearText.optString("type", "");
        String transferId = validateMessageId(clearText.optString("id", ""));
        switch (type) {
            case "media_start": {
                String sender = validateSender(clearText.getString("sender"));
                String mimeType = clearText.getString("mime");
                String name = clearText.getString("name");
                long size = clearText.getLong("size");
                validateMedia(mimeType, name, size);
                return DecryptedPacket.mediaStart(
                        transferId,
                        null,
                        sender,
                        mimeType,
                        name,
                        size,
                        readTimestamp(clearText),
                        false,
                        null
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
                if (
                        chunks <= 0 ||
                        chunks > MAX_MEDIA_CHUNKS ||
                        !digest.matches("^[a-f0-9]{64}$")
                ) {
                    throw new GeneralSecurityException("Medya doğrulaması geçersiz.");
                }
                return DecryptedPacket.mediaEnd(transferId, chunks, digest);
            }
            default:
                throw new GeneralSecurityException("Mesaj türü desteklenmiyor.");
        }
    }

    private JSONObject baseMessage(
            String type,
            String messageId,
            String deviceId,
            String sender,
            long sentAt,
            ChatEvent.Reply reply
    ) throws JSONException, GeneralSecurityException {
        JSONObject clearText = new JSONObject();
        clearText.put("v", 3);
        clearText.put("type", type);
        clearText.put("id", validateMessageId(messageId));
        clearText.put("device", validateDeviceId(deviceId));
        clearText.put("sender", validateSender(sender));
        clearText.put("sentAt", sentAt);
        if (reply != null) {
            JSONObject replyObject = new JSONObject();
            replyObject.put("id", validateMessageId(reply.messageId));
            replyObject.put("sender", validateSender(reply.sender));
            String preview = reply.preview == null ? "" : reply.preview.trim();
            if (preview.isEmpty() || preview.length() > 160) {
                throw new GeneralSecurityException("Yanıt özeti geçersiz.");
            }
            replyObject.put("preview", preview);
            clearText.put("reply", replyObject);
        }
        return clearText;
    }

    private ChatEvent.Reply readReply(JSONObject clearText)
            throws JSONException, GeneralSecurityException {
        JSONObject reply = clearText.optJSONObject("reply");
        if (reply == null) {
            return null;
        }
        String messageId = validateMessageId(reply.optString("id", ""));
        String sender = validateSender(reply.optString("sender", ""));
        String preview = reply.optString("preview", "").trim();
        if (preview.isEmpty() || preview.length() > 160) {
            throw new GeneralSecurityException("Yanıt özeti geçersiz.");
        }
        return new ChatEvent.Reply(messageId, sender, preview);
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
        if (sender == null || sender.trim().isEmpty() || sender.length() > 24) {
            throw new GeneralSecurityException("Gönderen geçersiz.");
        }
        return sender;
    }

    private static String validateMessage(String message) throws GeneralSecurityException {
        if (message == null || message.trim().isEmpty() || message.length() > 2_000) {
            throw new GeneralSecurityException("Mesaj içeriği geçersiz.");
        }
        return message;
    }

    private static String validateMessageId(String messageId) throws GeneralSecurityException {
        if (messageId == null || !messageId.matches("^[a-f0-9]{32}$")) {
            throw new GeneralSecurityException("Mesaj kimliği geçersiz.");
        }
        return messageId;
    }

    private static String validateDeviceId(String deviceId) throws GeneralSecurityException {
        if (deviceId == null || !deviceId.matches("^[a-f0-9]{32}$")) {
            throw new GeneralSecurityException("Cihaz kimliği geçersiz.");
        }
        return deviceId;
    }

    private static void validateMedia(String mimeType, String name, long size)
            throws GeneralSecurityException {
        long mediaLimit = mimeType != null && mimeType.startsWith("image/")
                ? MAX_IMAGE_BYTES
                : MAX_VIDEO_BYTES;
        if (
                mimeType == null ||
                (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) ||
                mimeType.length() > 80 ||
                name == null ||
                name.trim().isEmpty() ||
                name.length() > 120 ||
                size <= 0 ||
                size > mediaLimit
        ) {
            throw new GeneralSecurityException("Medya bilgisi geçersiz.");
        }
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
        static final String RECEIPT = "receipt";

        final String type;
        final String messageId;
        final String transferId;
        final String deviceId;
        final String sender;
        final String text;
        final String mimeType;
        final String displayName;
        final String digest;
        final String receiptState;
        final long size;
        final long sentAt;
        final int index;
        final int chunks;
        final byte[] data;
        final ChatEvent.Reply reply;
        final boolean viewOnce;

        private DecryptedPacket(
                String type,
                String messageId,
                String transferId,
                String deviceId,
                String sender,
                String text,
                String mimeType,
                String displayName,
                String digest,
                String receiptState,
                long size,
                long sentAt,
                int index,
                int chunks,
                byte[] data,
                ChatEvent.Reply reply,
                boolean viewOnce
        ) {
            this.type = type;
            this.messageId = messageId;
            this.transferId = transferId;
            this.deviceId = deviceId;
            this.sender = sender;
            this.text = text;
            this.mimeType = mimeType;
            this.displayName = displayName;
            this.digest = digest;
            this.receiptState = receiptState;
            this.size = size;
            this.sentAt = sentAt;
            this.index = index;
            this.chunks = chunks;
            this.data = data;
            this.reply = reply;
            this.viewOnce = viewOnce;
        }

        static DecryptedPacket text(
                String messageId,
                String deviceId,
                String sender,
                String text,
                long sentAt,
                ChatEvent.Reply reply
        ) {
            return new DecryptedPacket(
                    TEXT, messageId, null, deviceId, sender, text, null, null,
                    null, null, 0, sentAt, 0, 0, null, reply, false
            );
        }

        static DecryptedPacket mediaStart(
                String id,
                String deviceId,
                String sender,
                String mime,
                String name,
                long size,
                long sentAt,
                boolean viewOnce,
                ChatEvent.Reply reply
        ) {
            return new DecryptedPacket(
                    MEDIA_START, id, id, deviceId, sender, null, mime, name,
                    null, null, size, sentAt, 0, 0, null, reply, viewOnce
            );
        }

        static DecryptedPacket mediaChunk(String id, int index, byte[] data) {
            return new DecryptedPacket(
                    MEDIA_CHUNK, null, id, null, null, null, null, null,
                    null, null, 0, 0, index, 0, data, null, false
            );
        }

        static DecryptedPacket mediaEnd(String id, int chunks, String digest) {
            return new DecryptedPacket(
                    MEDIA_END, null, id, null, null, null, null, null,
                    digest, null, 0, 0, 0, chunks, null, null, false
            );
        }

        static DecryptedPacket receipt(String id, String deviceId, String state) {
            return new DecryptedPacket(
                    RECEIPT, id, null, deviceId, null, null, null, null,
                    null, state, 0, 0, 0, 0, null, null, false
            );
        }
    }
}

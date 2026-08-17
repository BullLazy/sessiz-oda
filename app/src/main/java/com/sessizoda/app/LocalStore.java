package com.sessizoda.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class LocalStore {
    private static final String KEY_ALIAS = "sessiz_oda_local_store_v1";
    private static final byte[] STATE_MAGIC = {'S', 'O', 'S', 1};
    private static final byte[] MEDIA_MAGIC = {'S', 'O', 'M', 1};
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final int MAX_ROOMS = 30;
    private static final int MAX_EVENTS = 150;
    private static final long MAX_STATE_BYTES = 32L * 1024L * 1024L;

    private static LocalStore instance;

    private final File rootDirectory;
    private final File stateFile;
    private final File mediaDirectory;
    private State state;
    private boolean loaded;

    static synchronized LocalStore get(Context context) {
        if (instance == null) {
            instance = new LocalStore(context.getApplicationContext());
        }
        return instance;
    }

    private LocalStore(Context context) {
        rootDirectory = new File(context.getNoBackupFilesDir(), "saved-chat");
        stateFile = new File(rootDirectory, "state.enc");
        mediaDirectory = new File(rootDirectory, "media");
    }

    synchronized List<SavedRoom> getSavedRooms()
            throws IOException, GeneralSecurityException {
        ensureLoaded();
        boolean changed = state.dirty;
        long now = System.currentTimeMillis();
        for (RoomRecord room : state.rooms.values()) {
            changed |= pruneExpired(room, now);
        }
        if (changed) {
            saveState();
        }
        List<SavedRoom> rooms = new ArrayList<>();
        for (RoomRecord room : state.rooms.values()) {
            rooms.add(new SavedRoom(
                    room.key,
                    room.server,
                    room.room,
                    room.displayName,
                    room.retentionMs,
                    room.lastJoinedAt,
                    RetentionPolicy.expiresAt(room.historyStartedAt, room.retentionMs),
                    room.events.size(),
                    room.hasNotificationAccess()
            ));
        }
        rooms.sort((first, second) -> Long.compare(second.lastJoinedAt, first.lastJoinedAt));
        return rooms;
    }

    synchronized NotificationSnapshot getNotificationSnapshot()
            throws IOException, GeneralSecurityException {
        ensureLoaded();
        boolean changed = state.dirty;
        long now = System.currentTimeMillis();
        List<NotificationRoom> rooms = new ArrayList<>();
        for (RoomRecord room : state.rooms.values()) {
            changed |= pruneExpired(room, now);
            if (room.hasNotificationAccess()) {
                rooms.add(new NotificationRoom(
                        room.server,
                        room.roomId,
                        room.authProof,
                        room.lastJoinedAt
                ));
            }
        }
        if (changed) {
            saveState();
        }
        rooms.sort((first, second) -> Long.compare(second.lastJoinedAt, first.lastJoinedAt));
        return new NotificationSnapshot(state.clientId, rooms);
    }

    synchronized RoomHistory prepareRoom(
            String server,
            String roomName,
            String displayName,
            String roomId,
            String authProof,
            long requestedRetention,
            File sessionDirectory
    ) throws IOException, GeneralSecurityException {
        ensureLoaded();
        if (!validHex(roomId, 64) || !validHex(authProof, 64)) {
            throw new GeneralSecurityException("Bildirim kimliği geçersiz.");
        }
        long now = System.currentTimeMillis();
        long retentionMs = RetentionPolicy.normalize(requestedRetention);
        String key = roomKey(server, roomName);
        RoomRecord room = state.rooms.get(key);
        if (room == null) {
            room = new RoomRecord(
                    key,
                    server,
                    roomName,
                    displayName,
                    roomId,
                    authProof,
                    retentionMs,
                    now,
                    now
            );
            state.rooms.put(key, room);
        } else {
            room.server = server;
            room.room = roomName;
            room.displayName = displayName;
            room.roomId = roomId;
            room.authProof = authProof;
            room.retentionMs = retentionMs;
            room.lastJoinedAt = now;
            pruneExpired(room, now);
            if (room.historyStartedAt <= 0) {
                room.historyStartedAt = now;
            }
        }
        trimRooms();
        saveState();

        if (!sessionDirectory.isDirectory() && !sessionDirectory.mkdirs()) {
            throw new IOException("Oturum klasörü hazırlanamadı.");
        }
        List<ChatEvent> events = materializeEvents(room, sessionDirectory);
        return new RoomHistory(
                key,
                RetentionPolicy.expiresAt(room.historyStartedAt, room.retentionMs),
                events,
                state.clientId
        );
    }

    synchronized long appendEvent(String roomKey, ChatEvent event)
            throws IOException, GeneralSecurityException {
        if (event.type == ChatEvent.TYPE_SYSTEM || event.viewOnce) {
            return 0;
        }
        ensureLoaded();
        RoomRecord room = state.rooms.get(roomKey);
        if (room == null) {
            throw new IOException("Kayıtlı oda bulunamadı.");
        }
        long now = System.currentTimeMillis();
        pruneExpired(room, now);
        if (room.historyStartedAt <= 0) {
            room.historyStartedAt = now;
        }

        StoredEvent stored;
        File newEncryptedMedia = null;
        if (event.type == ChatEvent.TYPE_TEXT) {
            stored = StoredEvent.text(event);
        } else if (event.type == ChatEvent.TYPE_MEDIA && event.mediaFile != null) {
            if (!event.mediaFile.isFile() || event.mediaFile.length() != event.size) {
                throw new IOException("Medya geçmişe kaydedilemedi.");
            }
            String mediaId = UUID.randomUUID().toString().replace("-", "");
            newEncryptedMedia = new File(mediaDirectory, mediaId + ".enc");
            encryptMedia(event.mediaFile, newEncryptedMedia);
            stored = StoredEvent.media(event, mediaId);
        } else {
            throw new IOException("Sohbet olayı geçersiz.");
        }

        room.events.add(stored);
        List<String> removedMedia = new ArrayList<>();
        while (room.events.size() > MAX_EVENTS) {
            StoredEvent removed = room.events.remove(0);
            if (removed.mediaId != null) {
                removedMedia.add(removed.mediaId);
            }
        }
        try {
            saveState();
        } catch (IOException | GeneralSecurityException exception) {
            room.events.remove(stored);
            if (newEncryptedMedia != null) {
                newEncryptedMedia.delete();
            }
            throw exception;
        }
        for (String mediaId : removedMedia) {
            deleteEncryptedMedia(mediaId);
        }
        return RetentionPolicy.expiresAt(room.historyStartedAt, room.retentionMs);
    }

    synchronized void updateEventStatus(String roomKey, String messageId, int status)
            throws IOException, GeneralSecurityException {
        if (!validHex(messageId, 32) || status < ChatEvent.STATUS_SENT || status > ChatEvent.STATUS_SEEN) {
            return;
        }
        ensureLoaded();
        RoomRecord room = state.rooms.get(roomKey);
        if (room == null) {
            return;
        }
        for (StoredEvent event : room.events) {
            if (messageId.equals(event.messageId) && status > event.deliveryStatus) {
                event.deliveryStatus = status;
                saveState();
                return;
            }
        }
    }

    synchronized void clearHistory(String roomKey)
            throws IOException, GeneralSecurityException {
        ensureLoaded();
        RoomRecord room = state.rooms.get(roomKey);
        if (room == null) {
            return;
        }
        List<String> mediaIds = mediaIds(room.events);
        room.events.clear();
        room.historyStartedAt = 0;
        saveState();
        for (String mediaId : mediaIds) {
            deleteEncryptedMedia(mediaId);
        }
    }

    private List<ChatEvent> materializeEvents(RoomRecord room, File sessionDirectory)
            throws IOException, GeneralSecurityException {
        List<ChatEvent> result = new ArrayList<>();
        boolean changed = false;
        Iterator<StoredEvent> iterator = room.events.iterator();
        while (iterator.hasNext()) {
            StoredEvent stored = iterator.next();
            ChatEvent.Reply reply = stored.reply();
            if (stored.type == ChatEvent.TYPE_TEXT) {
                result.add(ChatEvent.text(
                        stored.id,
                        stored.messageId,
                        stored.sender,
                        stored.text,
                        stored.sentAt,
                        stored.own,
                        reply,
                        stored.deliveryStatus
                ));
                continue;
            }
            File encrypted = new File(mediaDirectory, stored.mediaId + ".enc");
            File plain = new File(
                    sessionDirectory,
                    stored.mediaId + extensionFor(stored.mimeType)
            );
            try {
                decryptMedia(encrypted, plain, stored.size);
                result.add(ChatEvent.media(
                        stored.id,
                        stored.messageId,
                        stored.sender,
                        stored.sentAt,
                        stored.own,
                        plain,
                        stored.mimeType,
                        stored.displayName,
                        stored.size,
                        reply,
                        false,
                        false,
                        stored.deliveryStatus
                ));
            } catch (IOException | GeneralSecurityException exception) {
                plain.delete();
                encrypted.delete();
                iterator.remove();
                changed = true;
            }
        }
        if (changed) {
            saveState();
        }
        return result;
    }

    private void ensureLoaded() throws IOException, GeneralSecurityException {
        if (loaded) {
            return;
        }
        ensureDirectories();
        if (!stateFile.isFile()) {
            state = new State();
            loaded = true;
            cleanupOrphanedMedia();
            return;
        }
        byte[] packet = null;
        try {
            packet = readBounded(stateFile, MAX_STATE_BYTES);
            byte[] clear = decryptBytes(packet, STATE_MAGIC);
            try {
                state = State.fromJson(new JSONObject(new String(clear, StandardCharsets.UTF_8)));
            } finally {
                Arrays.fill(clear, (byte) 0);
            }
        } catch (JSONException | IOException | GeneralSecurityException exception) {
            resetStoredData();
            state = new State();
        } finally {
            if (packet != null) {
                Arrays.fill(packet, (byte) 0);
            }
        }
        loaded = true;
        cleanupOrphanedMedia();
    }

    private void saveState() throws IOException, GeneralSecurityException {
        ensureDirectories();
        byte[] clear = state.toJson().toString().getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = null;
        File temporary = new File(rootDirectory, "state.tmp");
        try {
            encrypted = encryptBytes(clear, STATE_MAGIC);
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write(encrypted);
                output.flush();
                output.getFD().sync();
            }
            moveReplacing(temporary, stateFile);
            state.dirty = false;
        } finally {
            Arrays.fill(clear, (byte) 0);
            if (encrypted != null) {
                Arrays.fill(encrypted, (byte) 0);
            }
            temporary.delete();
        }
    }

    private byte[] encryptBytes(byte[] clear, byte[] magic)
            throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE_BYTES];
        new java.security.SecureRandom().nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] body = cipher.doFinal(clear);
            byte[] result = new byte[magic.length + nonce.length + body.length];
            System.arraycopy(magic, 0, result, 0, magic.length);
            System.arraycopy(nonce, 0, result, magic.length, nonce.length);
            System.arraycopy(body, 0, result, magic.length + nonce.length, body.length);
            Arrays.fill(body, (byte) 0);
            return result;
        } finally {
            Arrays.fill(nonce, (byte) 0);
        }
    }

    private byte[] decryptBytes(byte[] packet, byte[] magic)
            throws GeneralSecurityException {
        int minimum = magic.length + NONCE_BYTES + 16;
        if (packet.length < minimum) {
            throw new GeneralSecurityException("Şifreli yerel kayıt geçersiz.");
        }
        for (int index = 0; index < magic.length; index++) {
            if (packet[index] != magic[index]) {
                throw new GeneralSecurityException("Şifreli yerel kayıt geçersiz.");
            }
        }
        byte[] nonce = Arrays.copyOfRange(packet, magic.length, magic.length + NONCE_BYTES);
        byte[] body = Arrays.copyOfRange(packet, magic.length + NONCE_BYTES, packet.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(body);
        } finally {
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(body, (byte) 0);
        }
    }

    private void encryptMedia(File plain, File encrypted)
            throws IOException, GeneralSecurityException {
        ensureDirectories();
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] buffer = new byte[BUFFER_BYTES];
        new java.security.SecureRandom().nextBytes(nonce);
        try (FileInputStream input = new FileInputStream(plain);
             FileOutputStream output = new FileOutputStream(encrypted, false)) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(TAG_BITS, nonce));
            output.write(MEDIA_MAGIC);
            output.write(nonce);
            int read;
            while ((read = input.read(buffer)) != -1) {
                byte[] block = cipher.update(buffer, 0, read);
                if (block != null) {
                    output.write(block);
                    Arrays.fill(block, (byte) 0);
                }
            }
            byte[] finalBytes = cipher.doFinal();
            output.write(finalBytes);
            Arrays.fill(finalBytes, (byte) 0);
            output.flush();
            output.getFD().sync();
        } catch (IOException | GeneralSecurityException exception) {
            encrypted.delete();
            throw exception;
        } finally {
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private void decryptMedia(File encrypted, File plain, long expectedSize)
            throws IOException, GeneralSecurityException {
        if (!encrypted.isFile()) {
            throw new IOException("Şifreli medya bulunamadı.");
        }
        byte[] header = new byte[MEDIA_MAGIC.length + NONCE_BYTES];
        byte[] buffer = new byte[BUFFER_BYTES];
        long written = 0;
        try (FileInputStream input = new FileInputStream(encrypted);
             FileOutputStream output = new FileOutputStream(plain, false)) {
            if (input.read(header) != header.length) {
                throw new IOException("Şifreli medya geçersiz.");
            }
            for (int index = 0; index < MEDIA_MAGIC.length; index++) {
                if (header[index] != MEDIA_MAGIC[index]) {
                    throw new GeneralSecurityException("Şifreli medya geçersiz.");
                }
            }
            byte[] nonce = Arrays.copyOfRange(header, MEDIA_MAGIC.length, header.length);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(
                        Cipher.DECRYPT_MODE,
                        getOrCreateKey(),
                        new GCMParameterSpec(TAG_BITS, nonce)
                );
                int read;
                while ((read = input.read(buffer)) != -1) {
                    byte[] block = cipher.update(buffer, 0, read);
                    if (block != null) {
                        output.write(block);
                        written += block.length;
                        Arrays.fill(block, (byte) 0);
                    }
                    if (written > expectedSize) {
                        throw new IOException("Medya boyutu geçersiz.");
                    }
                }
                byte[] finalBytes = cipher.doFinal();
                output.write(finalBytes);
                written += finalBytes.length;
                Arrays.fill(finalBytes, (byte) 0);
            } finally {
                Arrays.fill(nonce, (byte) 0);
            }
            if (written != expectedSize) {
                throw new IOException("Medya boyutu geçersiz.");
            }
            output.flush();
            output.getFD().sync();
        } catch (IOException | GeneralSecurityException exception) {
            plain.delete();
            throw exception;
        } finally {
            Arrays.fill(header, (byte) 0);
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        try {
            keyStore.load(null);
        } catch (IOException exception) {
            throw new GeneralSecurityException("Cihaz anahtarı açılamadı.", exception);
        }
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private boolean pruneExpired(RoomRecord room, long now) {
        long expiresAt = RetentionPolicy.expiresAt(room.historyStartedAt, room.retentionMs);
        if (expiresAt <= 0 || now < expiresAt) {
            return false;
        }
        List<String> ids = mediaIds(room.events);
        room.events.clear();
        room.historyStartedAt = 0;
        for (String id : ids) {
            deleteEncryptedMedia(id);
        }
        state.dirty = true;
        return true;
    }

    private void trimRooms() {
        while (state.rooms.size() > MAX_ROOMS) {
            RoomRecord oldest = state.rooms.values().stream()
                    .min(Comparator.comparingLong(value -> value.lastJoinedAt))
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            state.rooms.remove(oldest.key);
            for (String mediaId : mediaIds(oldest.events)) {
                deleteEncryptedMedia(mediaId);
            }
        }
    }

    private void cleanupOrphanedMedia() {
        Set<String> used = new HashSet<>();
        for (RoomRecord room : state.rooms.values()) {
            used.addAll(mediaIds(room.events));
        }
        File[] files = mediaDirectory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            if (
                    file.isFile() &&
                    name.endsWith(".enc") &&
                    !used.contains(name.substring(0, name.length() - 4))
            ) {
                file.delete();
            }
        }
    }

    private void ensureDirectories() throws IOException {
        if (!rootDirectory.isDirectory() && !rootDirectory.mkdirs()) {
            throw new IOException("Yerel kayıt klasörü oluşturulamadı.");
        }
        if (!mediaDirectory.isDirectory() && !mediaDirectory.mkdirs()) {
            throw new IOException("Yerel medya klasörü oluşturulamadı.");
        }
    }

    private void resetStoredData() {
        stateFile.delete();
        clearDirectory(mediaDirectory);
        mediaDirectory.mkdirs();
    }

    private void deleteEncryptedMedia(String mediaId) {
        if (validHex(mediaId, 32)) {
            new File(mediaDirectory, mediaId + ".enc").delete();
        }
    }

    private static List<String> mediaIds(List<StoredEvent> events) {
        List<String> result = new ArrayList<>();
        for (StoredEvent event : events) {
            if (event.mediaId != null) {
                result.add(event.mediaId);
            }
        }
        return result;
    }

    private static byte[] readBounded(File file, long limit) throws IOException {
        long length = file.length();
        if (length <= 0 || length > limit || length > Integer.MAX_VALUE) {
            throw new IOException("Yerel kayıt boyutu geçersiz.");
        }
        byte[] bytes = new byte[(int) length];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read == -1) {
                    throw new IOException("Yerel kayıt eksik.");
                }
                offset += read;
            }
            if (input.read() != -1) {
                throw new IOException("Yerel kayıt boyutu değişti.");
            }
        }
        return bytes;
    }

    private static void moveReplacing(File source, File destination) throws IOException {
        try {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static String roomKey(String server, String roomName)
            throws GeneralSecurityException {
        String normalizedServer = server.trim().toLowerCase(Locale.ROOT);
        String normalizedRoom = Normalizer.normalize(roomName.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((normalizedServer + "\n" + normalizedRoom).getBytes(StandardCharsets.UTF_8));
        return CryptoBox.toHex(digest);
    }

    private static boolean validHex(String value, int length) {
        return value != null && value.matches("^[a-f0-9]{" + length + "}$");
    }

    private static String extensionFor(String mimeType) {
        if ("image/jpeg".equals(mimeType)) {
            return ".jpg";
        }
        if ("image/png".equals(mimeType)) {
            return ".png";
        }
        if ("image/webp".equals(mimeType)) {
            return ".webp";
        }
        if ("video/mp4".equals(mimeType)) {
            return ".mp4";
        }
        return mimeType != null && mimeType.startsWith("image/") ? ".image" : ".video";
    }

    private static void clearDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    clearDirectory(file);
                }
                file.delete();
            }
        }
        directory.delete();
    }

    static final class SavedRoom {
        final String key;
        final String server;
        final String room;
        final String displayName;
        final long retentionMs;
        final long lastJoinedAt;
        final long expiresAt;
        final int eventCount;
        final boolean notificationsReady;

        SavedRoom(
                String key,
                String server,
                String room,
                String displayName,
                long retentionMs,
                long lastJoinedAt,
                long expiresAt,
                int eventCount,
                boolean notificationsReady
        ) {
            this.key = key;
            this.server = server;
            this.room = room;
            this.displayName = displayName;
            this.retentionMs = retentionMs;
            this.lastJoinedAt = lastJoinedAt;
            this.expiresAt = expiresAt;
            this.eventCount = eventCount;
            this.notificationsReady = notificationsReady;
        }
    }

    static final class NotificationRoom {
        final String server;
        final String roomId;
        final String authProof;
        final long lastJoinedAt;

        NotificationRoom(String server, String roomId, String authProof, long lastJoinedAt) {
            this.server = server;
            this.roomId = roomId;
            this.authProof = authProof;
            this.lastJoinedAt = lastJoinedAt;
        }
    }

    static final class NotificationSnapshot {
        final String clientId;
        final List<NotificationRoom> rooms;

        NotificationSnapshot(String clientId, List<NotificationRoom> rooms) {
            this.clientId = clientId;
            this.rooms = rooms;
        }
    }

    static final class RoomHistory {
        final String roomKey;
        final long expiresAt;
        final List<ChatEvent> events;
        final String clientId;

        RoomHistory(String roomKey, long expiresAt, List<ChatEvent> events, String clientId) {
            this.roomKey = roomKey;
            this.expiresAt = expiresAt;
            this.events = events;
            this.clientId = clientId;
        }
    }

    private static final class State {
        final Map<String, RoomRecord> rooms = new LinkedHashMap<>();
        String clientId = UUID.randomUUID().toString().replace("-", "");
        boolean dirty;

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            JSONArray roomArray = new JSONArray();
            try {
                object.put("v", 1);
                object.put("client", clientId);
                for (RoomRecord room : rooms.values()) {
                    roomArray.put(room.toJson());
                }
                object.put("rooms", roomArray);
            } catch (JSONException exception) {
                throw new IllegalStateException(exception);
            }
            return object;
        }

        static State fromJson(JSONObject object) throws JSONException {
            State result = new State();
            String storedClient = object.optString(
                    "client",
                    object.optString("clientId", "")
            );
            if (validHex(storedClient, 32)) {
                result.clientId = storedClient;
            } else {
                result.dirty = true;
            }
            JSONArray roomArray = object.optJSONArray("rooms");
            if (roomArray == null) {
                result.dirty = true;
                return result;
            }
            for (int index = 0; index < roomArray.length(); index++) {
                JSONObject roomObject = roomArray.optJSONObject(index);
                if (roomObject == null) {
                    result.dirty = true;
                    continue;
                }
                try {
                    RoomRecord room = RoomRecord.fromJson(roomObject);
                    result.rooms.put(room.key, room);
                    result.dirty |= room.dirty;
                } catch (JSONException | IllegalArgumentException exception) {
                    result.dirty = true;
                }
            }
            return result;
        }
    }

    private static final class RoomRecord {
        final String key;
        final List<StoredEvent> events = new ArrayList<>();
        String server;
        String room;
        String displayName;
        String roomId;
        String authProof;
        long retentionMs;
        long lastJoinedAt;
        long historyStartedAt;
        boolean dirty;

        RoomRecord(
                String key,
                String server,
                String room,
                String displayName,
                String roomId,
                String authProof,
                long retentionMs,
                long lastJoinedAt,
                long historyStartedAt
        ) {
            this.key = key;
            this.server = server;
            this.room = room;
            this.displayName = displayName;
            this.roomId = roomId;
            this.authProof = authProof;
            this.retentionMs = retentionMs;
            this.lastJoinedAt = lastJoinedAt;
            this.historyStartedAt = historyStartedAt;
        }

        boolean hasNotificationAccess() {
            return server != null &&
                    server.startsWith("wss://") &&
                    validHex(roomId, 64) &&
                    validHex(authProof, 64);
        }

        JSONObject toJson() throws JSONException {
            JSONObject object = new JSONObject();
            object.put("key", key);
            object.put("server", server);
            object.put("room", room);
            object.put("name", displayName);
            object.put("roomId", roomId);
            object.put("proof", authProof);
            object.put("retention", retentionMs);
            object.put("joined", lastJoinedAt);
            object.put("started", historyStartedAt);
            JSONArray eventArray = new JSONArray();
            for (StoredEvent event : events) {
                eventArray.put(event.toJson());
            }
            object.put("events", eventArray);
            return object;
        }

        static RoomRecord fromJson(JSONObject object) throws JSONException {
            String key = object.getString("key");
            String server = object.getString("server");
            String roomName = object.getString("room");
            String displayName = object.optString(
                    "displayName",
                    object.optString("name", object.optString("display", ""))
            );
            String roomId = object.optString("roomId", object.optString("id", ""));
            String proof = object.optString("proof", object.optString("authProof", ""));
            long retention = object.optLong(
                    "retentionMs",
                    object.optLong("retention", RetentionPolicy.DEFAULT_MS)
            );
            long joined = object.optLong(
                    "joined",
                    object.optLong("lastJoinedAt", System.currentTimeMillis())
            );
            long started = object.optLong(
                    "started",
                    object.optLong("historyStartedAt", 0)
            );
            if (
                    !validHex(key, 64) ||
                    server.isEmpty() ||
                    roomName.isEmpty() ||
                    displayName.isEmpty() ||
                    !RetentionPolicy.isSupported(retention)
            ) {
                throw new IllegalArgumentException("Kayıtlı oda geçersiz.");
            }
            RoomRecord result = new RoomRecord(
                    key,
                    server,
                    roomName,
                    displayName,
                    roomId,
                    proof,
                    retention,
                    joined,
                    started
            );
            JSONArray eventArray = object.optJSONArray("events");
            if (eventArray != null) {
                int start = Math.max(0, eventArray.length() - MAX_EVENTS);
                for (int index = start; index < eventArray.length(); index++) {
                    JSONObject eventObject = eventArray.optJSONObject(index);
                    if (eventObject == null) {
                        result.dirty = true;
                        continue;
                    }
                    try {
                        result.events.add(StoredEvent.fromJson(eventObject));
                    } catch (JSONException | IllegalArgumentException exception) {
                        result.dirty = true;
                    }
                }
            }
            return result;
        }
    }

    private static final class StoredEvent {
        long id;
        int type;
        String messageId;
        String sender;
        String text;
        long sentAt;
        boolean own;
        String mediaId;
        String mimeType;
        String displayName;
        long size;
        String replyMessageId;
        String replySender;
        String replyPreview;
        int deliveryStatus;

        static StoredEvent text(ChatEvent event) {
            StoredEvent stored = common(event);
            stored.type = ChatEvent.TYPE_TEXT;
            stored.text = event.text;
            return stored;
        }

        static StoredEvent media(ChatEvent event, String mediaId) {
            StoredEvent stored = common(event);
            stored.type = ChatEvent.TYPE_MEDIA;
            stored.mediaId = mediaId;
            stored.mimeType = event.mimeType;
            stored.displayName = event.displayName;
            stored.size = event.size;
            return stored;
        }

        private static StoredEvent common(ChatEvent event) {
            StoredEvent stored = new StoredEvent();
            stored.id = event.id;
            stored.messageId = event.messageId;
            stored.sender = event.sender;
            stored.sentAt = event.sentAt;
            stored.own = event.own;
            stored.replyMessageId = event.replyMessageId;
            stored.replySender = event.replySender;
            stored.replyPreview = event.replyPreview;
            stored.deliveryStatus = event.deliveryStatus;
            return stored;
        }

        ChatEvent.Reply reply() {
            if (
                    !validHex(replyMessageId, 32) ||
                    replySender == null ||
                    replySender.isEmpty() ||
                    replyPreview == null ||
                    replyPreview.isEmpty()
            ) {
                return null;
            }
            return new ChatEvent.Reply(replyMessageId, replySender, replyPreview);
        }

        JSONObject toJson() throws JSONException {
            JSONObject object = new JSONObject();
            object.put("id", id);
            object.put("type", type);
            object.put("sender", sender);
            object.put("sentAt", sentAt);
            object.put("own", own);
            if (messageId != null) {
                object.put("mid", messageId);
            }
            if (replyMessageId != null) {
                object.put("replyId", replyMessageId);
                object.put("replySender", replySender);
                object.put("replyPreview", replyPreview);
            }
            if (deliveryStatus != ChatEvent.STATUS_NONE) {
                object.put("status", deliveryStatus);
            }
            if (type == ChatEvent.TYPE_TEXT) {
                object.put("text", text);
            } else {
                object.put("media", mediaId);
                object.put("mime", mimeType);
                object.put("name", displayName);
                object.put("size", size);
            }
            return object;
        }

        static StoredEvent fromJson(JSONObject object) throws JSONException {
            StoredEvent stored = new StoredEvent();
            stored.id = object.getLong("id");
            stored.type = object.getInt("type");
            stored.sender = object.getString("sender");
            stored.sentAt = object.optLong("sentAt", object.optLong("sent", 0));
            stored.own = object.optBoolean("own", false);
            stored.messageId = object.optString("mid", null);
            stored.replyMessageId = object.optString("replyId", null);
            stored.replySender = object.optString("replySender", null);
            stored.replyPreview = object.optString("replyPreview", null);
            stored.deliveryStatus = object.optInt("status", ChatEvent.STATUS_NONE);
            if (
                    stored.id <= 0 ||
                    stored.sender.isEmpty() ||
                    stored.sender.length() > 24 ||
                    stored.sentAt <= 0 ||
                    (stored.messageId != null && !validHex(stored.messageId, 32)) ||
                    stored.deliveryStatus < ChatEvent.STATUS_NONE ||
                    stored.deliveryStatus > ChatEvent.STATUS_SEEN
            ) {
                throw new IllegalArgumentException("Kayıtlı ileti geçersiz.");
            }
            if (stored.type == ChatEvent.TYPE_TEXT) {
                stored.text = object.getString("text");
                if (stored.text.isEmpty() || stored.text.length() > 2_000) {
                    throw new IllegalArgumentException("Kayıtlı metin geçersiz.");
                }
                return stored;
            }
            if (stored.type != ChatEvent.TYPE_MEDIA) {
                throw new IllegalArgumentException("Kayıtlı ileti türü geçersiz.");
            }
            stored.mediaId = object.optString("media", object.optString("mediaId", ""));
            stored.mimeType = object.optString("mime", object.optString("mimeType", ""));
            stored.displayName = object.optString(
                    "name",
                    object.optString("displayName", "")
            );
            stored.size = object.getLong("size");
            if (
                    !validHex(stored.mediaId, 32) ||
                    (!stored.mimeType.startsWith("image/") &&
                            !stored.mimeType.startsWith("video/")) ||
                    stored.displayName.isEmpty() ||
                    stored.size <= 0
            ) {
                throw new IllegalArgumentException("Kayıtlı medya geçersiz.");
            }
            return stored;
        }
    }
}

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
        boolean changed = false;
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
                    room.events.size()
            ));
        }
        rooms.sort((first, second) -> Long.compare(second.lastJoinedAt, first.lastJoinedAt));
        return rooms;
    }

    synchronized RoomHistory prepareRoom(
            String server,
            String roomName,
            String displayName,
            long requestedRetention,
            File sessionDirectory
    ) throws IOException, GeneralSecurityException {
        ensureLoaded();
        long now = System.currentTimeMillis();
        long retentionMs = RetentionPolicy.normalize(requestedRetention);
        String key = roomKey(server, roomName);
        RoomRecord room = state.rooms.get(key);
        if (room == null) {
            room = new RoomRecord(key, server, roomName, displayName, retentionMs, now, now);
            state.rooms.put(key, room);
        } else {
            room.server = server;
            room.room = roomName;
            room.displayName = displayName;
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
                events
        );
    }

    synchronized long appendEvent(String roomKey, ChatEvent event)
            throws IOException, GeneralSecurityException {
        if (event.type == ChatEvent.TYPE_SYSTEM) {
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
            if (stored.type == ChatEvent.TYPE_TEXT) {
                result.add(ChatEvent.text(
                        stored.id,
                        stored.sender,
                        stored.text,
                        stored.sentAt,
                        stored.own
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
                        stored.sender,
                        stored.sentAt,
                        stored.own,
                        plain,
                        stored.mimeType,
                        stored.displayName,
                        stored.size
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
        byte[] clear;
        try {
            clear = state.toJson().toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException exception) {
            throw new IOException("Yerel geçmiş hazırlanamadı.", exception);
        }
        byte[] encrypted = encryptBytes(clear, STATE_MAGIC);
        Arrays.fill(clear, (byte) 0);
        File temporary = new File(rootDirectory, "state.tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(encrypted);
            output.flush();
            output.getFD().sync();
        } finally {
            Arrays.fill(encrypted, (byte) 0);
        }
        moveReplacing(temporary, stateFile);
    }

    private void ensureDirectories() throws IOException {
        if (!rootDirectory.isDirectory() && !rootDirectory.mkdirs()) {
            throw new IOException("Yerel veri alanı hazırlanamadı.");
        }
        if (!mediaDirectory.isDirectory() && !mediaDirectory.mkdirs()) {
            throw new IOException("Yerel medya alanı hazırlanamadı.");
        }
    }

    private boolean pruneExpired(RoomRecord room, long now) {
        if (!RetentionPolicy.isExpired(room.historyStartedAt, room.retentionMs, now)) {
            return false;
        }
        for (String mediaId : mediaIds(room.events)) {
            deleteEncryptedMedia(mediaId);
        }
        room.events.clear();
        room.historyStartedAt = 0;
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
        Set<String> expected = new HashSet<>();
        for (RoomRecord room : state.rooms.values()) {
            for (StoredEvent event : room.events) {
                if (event.mediaId != null) {
                    expected.add(event.mediaId + ".enc");
                }
            }
        }
        File[] files = mediaDirectory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!expected.contains(file.getName())) {
                file.delete();
            }
        }
    }

    private void encryptMedia(File source, File destination)
            throws IOException, GeneralSecurityException {
        ensureDirectories();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] nonce = cipher.getIV();
        File temporary = new File(mediaDirectory, destination.getName() + ".tmp");
        byte[] buffer = new byte[BUFFER_BYTES];
        try (
                FileInputStream input = new FileInputStream(source);
                FileOutputStream output = new FileOutputStream(temporary, false)
        ) {
            output.write(MEDIA_MAGIC);
            output.write(nonce);
            int read;
            while ((read = input.read(buffer)) != -1) {
                byte[] encrypted = cipher.update(buffer, 0, read);
                if (encrypted != null) {
                    output.write(encrypted);
                    Arrays.fill(encrypted, (byte) 0);
                }
            }
            byte[] finalBytes = cipher.doFinal();
            output.write(finalBytes);
            Arrays.fill(finalBytes, (byte) 0);
            output.flush();
            output.getFD().sync();
        } catch (IOException | GeneralSecurityException exception) {
            temporary.delete();
            throw exception;
        } finally {
            Arrays.fill(buffer, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
        }
        moveReplacing(temporary, destination);
    }

    private void decryptMedia(File source, File destination, long expectedSize)
            throws IOException, GeneralSecurityException {
        if (!source.isFile()) {
            throw new IOException("Kayıtlı medya bulunamadı.");
        }
        if (source.length() != expectedSize + MEDIA_MAGIC.length + NONCE_BYTES + 16L) {
            throw new IOException("Kayıtlı medya boyutu geçersiz.");
        }
        byte[] header = new byte[MEDIA_MAGIC.length];
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] buffer = new byte[BUFFER_BYTES];
        long written = 0;
        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        try (
                FileInputStream input = new FileInputStream(source);
                FileOutputStream output = new FileOutputStream(temporary, false)
        ) {
            readFully(input, header);
            readFully(input, nonce);
            if (!Arrays.equals(header, MEDIA_MAGIC)) {
                throw new IOException("Kayıtlı medya biçimi geçersiz.");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(TAG_BITS, nonce));
            int read;
            while ((read = input.read(buffer)) != -1) {
                byte[] clear = cipher.update(buffer, 0, read);
                if (clear != null) {
                    output.write(clear);
                    written += clear.length;
                    Arrays.fill(clear, (byte) 0);
                }
            }
            byte[] finalBytes = cipher.doFinal();
            output.write(finalBytes);
            written += finalBytes.length;
            Arrays.fill(finalBytes, (byte) 0);
            output.flush();
            output.getFD().sync();
        } catch (IOException | GeneralSecurityException exception) {
            temporary.delete();
            throw exception;
        } finally {
            Arrays.fill(header, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(buffer, (byte) 0);
        }
        if (written != expectedSize) {
            temporary.delete();
            throw new IOException("Kayıtlı medya boyutu geçersiz.");
        }
        moveReplacing(temporary, destination);
    }

    private byte[] encryptBytes(byte[] clear, byte[] magic)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] nonce = cipher.getIV();
        byte[] body = cipher.doFinal(clear);
        byte[] packet = new byte[magic.length + nonce.length + body.length];
        System.arraycopy(magic, 0, packet, 0, magic.length);
        System.arraycopy(nonce, 0, packet, magic.length, nonce.length);
        System.arraycopy(body, 0, packet, magic.length + nonce.length, body.length);
        Arrays.fill(nonce, (byte) 0);
        Arrays.fill(body, (byte) 0);
        return packet;
    }

    private byte[] decryptBytes(byte[] packet, byte[] magic)
            throws GeneralSecurityException, IOException {
        int bodyOffset = magic.length + NONCE_BYTES;
        if (packet.length <= bodyOffset + 16) {
            throw new IOException("Yerel veri biçimi geçersiz.");
        }
        for (int index = 0; index < magic.length; index++) {
            if (packet[index] != magic[index]) {
                throw new IOException("Yerel veri sürümü geçersiz.");
            }
        }
        byte[] nonce = Arrays.copyOfRange(packet, magic.length, bodyOffset);
        byte[] body = Arrays.copyOfRange(packet, bodyOffset, packet.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(body);
        } finally {
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(body, (byte) 0);
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

    private void resetStoredData() {
        stateFile.delete();
        File temporary = new File(rootDirectory, "state.tmp");
        temporary.delete();
        File[] mediaFiles = mediaDirectory.listFiles();
        if (mediaFiles != null) {
            for (File file : mediaFiles) {
                file.delete();
            }
        }
    }

    private void deleteEncryptedMedia(String mediaId) {
        if (mediaId != null) {
            new File(mediaDirectory, mediaId + ".enc").delete();
        }
    }

    private static List<String> mediaIds(List<StoredEvent> events) {
        List<String> ids = new ArrayList<>();
        for (StoredEvent event : events) {
            if (event.mediaId != null) {
                ids.add(event.mediaId);
            }
        }
        return ids;
    }

    private static byte[] readBounded(File file, long limit) throws IOException {
        long length = file.length();
        if (length <= 0 || length > limit || length > Integer.MAX_VALUE) {
            throw new IOException("Yerel veri boyutu geçersiz.");
        }
        byte[] data = new byte[(int) length];
        try (FileInputStream input = new FileInputStream(file)) {
            readFully(input, data);
            if (input.read() != -1) {
                Arrays.fill(data, (byte) 0);
                throw new IOException("Yerel veri değişti.");
            }
        }
        return data;
    }

    private static void readFully(FileInputStream input, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = input.read(target, offset, target.length - offset);
            if (read == -1) {
                throw new IOException("Dosya beklenenden kısa.");
            }
            offset += read;
        }
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
        String normalizedRoom = Normalizer.normalize(roomName.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        String normalizedServer = server.trim().toLowerCase(Locale.ROOT);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (normalizedServer + "\n" + normalizedRoom).getBytes(StandardCharsets.UTF_8)
        );
        String result = CryptoBox.toHex(digest);
        Arrays.fill(digest, (byte) 0);
        return result;
    }

    private static String extensionFor(String mimeType) {
        switch (mimeType) {
            case "image/jpeg":
                return ".jpg";
            case "image/png":
                return ".png";
            case "image/webp":
                return ".webp";
            case "image/gif":
                return ".gif";
            case "video/mp4":
                return ".mp4";
            case "video/webm":
                return ".webm";
            case "video/3gpp":
                return ".3gp";
            default:
                return mimeType.startsWith("image/") ? ".image" : ".video";
        }
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

        SavedRoom(
                String key,
                String server,
                String room,
                String displayName,
                long retentionMs,
                long lastJoinedAt,
                long expiresAt,
                int eventCount
        ) {
            this.key = key;
            this.server = server;
            this.room = room;
            this.displayName = displayName;
            this.retentionMs = retentionMs;
            this.lastJoinedAt = lastJoinedAt;
            this.expiresAt = expiresAt;
            this.eventCount = eventCount;
        }
    }

    static final class RoomHistory {
        final String roomKey;
        final long expiresAt;
        final List<ChatEvent> events;

        RoomHistory(String roomKey, long expiresAt, List<ChatEvent> events) {
            this.roomKey = roomKey;
            this.expiresAt = expiresAt;
            this.events = events;
        }
    }

    private static final class State {
        final Map<String, RoomRecord> rooms = new LinkedHashMap<>();

        JSONObject toJson() throws JSONException {
            JSONObject root = new JSONObject();
            root.put("v", 1);
            JSONArray roomArray = new JSONArray();
            for (RoomRecord room : rooms.values()) {
                roomArray.put(room.toJson());
            }
            root.put("rooms", roomArray);
            return root;
        }

        static State fromJson(JSONObject root) throws JSONException {
            if (root.optInt("v", 0) != 1) {
                throw new JSONException("Yerel veri sürümü desteklenmiyor.");
            }
            State state = new State();
            JSONArray roomArray = root.optJSONArray("rooms");
            if (roomArray == null) {
                return state;
            }
            for (int index = 0; index < roomArray.length() && state.rooms.size() < MAX_ROOMS; index++) {
                RoomRecord room = RoomRecord.fromJson(roomArray.optJSONObject(index));
                if (room != null) {
                    state.rooms.put(room.key, room);
                }
            }
            return state;
        }
    }

    private static final class RoomRecord {
        final String key;
        String server;
        String room;
        String displayName;
        long retentionMs;
        long lastJoinedAt;
        long historyStartedAt;
        final List<StoredEvent> events = new ArrayList<>();

        RoomRecord(
                String key,
                String server,
                String room,
                String displayName,
                long retentionMs,
                long lastJoinedAt,
                long historyStartedAt
        ) {
            this.key = key;
            this.server = server;
            this.room = room;
            this.displayName = displayName;
            this.retentionMs = retentionMs;
            this.lastJoinedAt = lastJoinedAt;
            this.historyStartedAt = historyStartedAt;
        }

        JSONObject toJson() throws JSONException {
            JSONObject object = new JSONObject();
            object.put("key", key);
            object.put("server", server);
            object.put("room", room);
            object.put("name", displayName);
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

        static RoomRecord fromJson(JSONObject object) {
            if (object == null) {
                return null;
            }
            String key = clean(object.optString("key", ""), 64);
            String server = clean(object.optString("server", ""), 300);
            String roomName = clean(object.optString("room", ""), 64);
            String name = clean(object.optString("name", ""), 24);
            long retention = object.optLong("retention", 0);
            long joined = object.optLong("joined", 0);
            long started = object.optLong("started", 0);
            if (
                    !key.matches("^[a-f0-9]{64}$") ||
                    server.isEmpty() ||
                    roomName.isEmpty() ||
                    name.isEmpty() ||
                    !RetentionPolicy.isSupported(retention) ||
                    joined <= 0 ||
                    started < 0
            ) {
                return null;
            }
            RoomRecord room = new RoomRecord(
                    key,
                    server,
                    roomName,
                    name,
                    retention,
                    joined,
                    started
            );
            JSONArray eventArray = object.optJSONArray("events");
            if (eventArray != null) {
                int start = Math.max(0, eventArray.length() - MAX_EVENTS);
                for (int index = start; index < eventArray.length(); index++) {
                    StoredEvent event = StoredEvent.fromJson(eventArray.optJSONObject(index));
                    if (event != null) {
                        room.events.add(event);
                    }
                }
            }
            return room;
        }
    }

    private static final class StoredEvent {
        final long id;
        final int type;
        final String sender;
        final String text;
        final long sentAt;
        final boolean own;
        final String mimeType;
        final String displayName;
        final long size;
        final String mediaId;

        StoredEvent(
                long id,
                int type,
                String sender,
                String text,
                long sentAt,
                boolean own,
                String mimeType,
                String displayName,
                long size,
                String mediaId
        ) {
            this.id = id;
            this.type = type;
            this.sender = sender;
            this.text = text;
            this.sentAt = sentAt;
            this.own = own;
            this.mimeType = mimeType;
            this.displayName = displayName;
            this.size = size;
            this.mediaId = mediaId;
        }

        static StoredEvent text(ChatEvent event) {
            return new StoredEvent(
                    event.id,
                    event.type,
                    event.sender,
                    event.text,
                    event.sentAt,
                    event.own,
                    null,
                    null,
                    0,
                    null
            );
        }

        static StoredEvent media(ChatEvent event, String mediaId) {
            return new StoredEvent(
                    event.id,
                    event.type,
                    event.sender,
                    null,
                    event.sentAt,
                    event.own,
                    event.mimeType,
                    event.displayName,
                    event.size,
                    mediaId
            );
        }

        JSONObject toJson() throws JSONException {
            JSONObject object = new JSONObject();
            object.put("id", id);
            object.put("type", type);
            object.put("sender", sender);
            object.put("sentAt", sentAt);
            object.put("own", own);
            if (type == ChatEvent.TYPE_TEXT) {
                object.put("text", text);
            } else {
                object.put("mime", mimeType);
                object.put("name", displayName);
                object.put("size", size);
                object.put("media", mediaId);
            }
            return object;
        }

        static StoredEvent fromJson(JSONObject object) {
            if (object == null) {
                return null;
            }
            long id = object.optLong("id", 0);
            int type = object.optInt("type", 0);
            String sender = clean(object.optString("sender", ""), 24);
            long sentAt = object.optLong("sentAt", 0);
            boolean own = object.optBoolean("own", false);
            if (id <= 0 || sender.isEmpty() || sentAt <= 0) {
                return null;
            }
            if (type == ChatEvent.TYPE_TEXT) {
                String text = bounded(object.optString("text", ""), 2_000);
                if (text.trim().isEmpty()) {
                    return null;
                }
                return new StoredEvent(id, type, sender, text, sentAt, own, null, null, 0, null);
            }
            if (type != ChatEvent.TYPE_MEDIA) {
                return null;
            }
            String mime = clean(object.optString("mime", ""), 80).toLowerCase(Locale.ROOT);
            String name = clean(object.optString("name", ""), 120);
            String mediaId = clean(object.optString("media", ""), 32);
            long size = object.optLong("size", 0);
            if (
                    (!mime.startsWith("image/") && !mime.startsWith("video/")) ||
                    name.isEmpty() ||
                    !mediaId.matches("^[a-f0-9]{32}$") ||
                    size <= 0 ||
                    size > CryptoBox.MAX_VIDEO_BYTES
            ) {
                return null;
            }
            return new StoredEvent(id, type, sender, null, sentAt, own, mime, name, size, mediaId);
        }
    }

    private static String clean(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    private static String bounded(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

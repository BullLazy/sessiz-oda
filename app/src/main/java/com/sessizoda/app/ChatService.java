package com.sessizoda.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChatService extends Service {
    static final String ACTION_CONNECT = "com.sessizoda.app.CONNECT";
    static final String EXTRA_SERVER = "server";
    static final String EXTRA_ROOM = "room";
    static final String EXTRA_SECRET = "secret";
    static final String EXTRA_NAME = "name";

    private static final String CONNECTION_CHANNEL = "connection";
    private static final String MESSAGE_CHANNEL = "messages";
    private static final int CONNECTION_NOTIFICATION_ID = 1001;
    private static final int MESSAGE_NOTIFICATION_ID = 2_000;
    private static final int MAX_EVENTS = 150;
    private static final long MAX_QUEUE_BYTES = 512L * 1024L;
    private static final long INCOMING_TIMEOUT_MS = 3L * 60L * 1_000L;

    interface Listener {
        void onSessionState(
                boolean connecting,
                boolean connected,
                String roomName,
                String displayName,
                int presence,
                boolean mediaSupported
        );

        void onEvent(ChatEvent event);

        void onError(String message);

        void onDisconnected();

        void onTransferProgress(String status, boolean active);
    }

    final class LocalBinder extends Binder {
        ChatService getService() {
            return ChatService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mediaExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean mediaSending = new AtomicBoolean(false);
    private final List<ChatEvent> events = new ArrayList<>();
    private final Map<String, IncomingMedia> incomingMedia = new HashMap<>();

    private Listener listener;
    private volatile ChatClient chatClient;
    private volatile CryptoBox cryptoBox;
    private volatile File sessionDirectory;
    private volatile String displayName = "";
    private String roomName = "";
    private boolean appVisible;
    private volatile boolean connecting;
    private volatile boolean connected;
    private volatile boolean mediaSupported;
    private int presence;
    private volatile int connectionGeneration;
    private long nextEventId = 1;
    private long lastDecryptWarningAt;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        clearDirectory(new File(getCacheDir(), "session-media"));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_CONNECT.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }

        showForegroundNotification(getString(R.string.status_connecting));
        String server = intent.getStringExtra(EXTRA_SERVER);
        String room = intent.getStringExtra(EXTRA_ROOM);
        String secret = intent.getStringExtra(EXTRA_SECRET);
        String name = intent.getStringExtra(EXTRA_NAME);
        intent.removeExtra(EXTRA_SECRET);
        if (server == null || room == null || secret == null || name == null) {
            failBeforeJoin("Bağlantı bilgileri eksik.");
            return START_NOT_STICKY;
        }
        beginSession(server, room, secret, name);
        return START_NOT_STICKY;
    }

    void setListener(Listener newListener, long afterEventId) {
        listener = newListener;
        if (newListener == null) {
            return;
        }
        emitState();
        for (ChatEvent event : new ArrayList<>(events)) {
            if (event.id > afterEventId) {
                newListener.onEvent(event);
            }
        }
    }

    void setAppVisible(boolean visible) {
        appVisible = visible;
        if (visible) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.cancel(MESSAGE_NOTIFICATION_ID);
            }
        }
    }

    boolean isConnected() {
        return connected;
    }

    boolean sendText(String message) {
        ChatClient currentClient = chatClient;
        CryptoBox currentCrypto = cryptoBox;
        if (!connected || currentClient == null || currentCrypto == null) {
            emitError("Bağlantı açık değil.");
            return false;
        }
        try {
            if (!currentClient.sendCipher("text", currentCrypto.encryptText(displayName, message))) {
                emitError("Mesaj gönderilemedi.");
                return false;
            }
            return true;
        } catch (GeneralSecurityException exception) {
            emitError("Mesaj şifrelenemedi.");
            return false;
        }
    }

    void sendMedia(Uri uri) {
        if (!connected || !mediaSupported || mediaSending.get()) {
            if (!connected) {
                emitError("Bağlantı açık değil.");
            } else if (!mediaSupported) {
                emitError("Sunucu medya desteği için güncellenmemiş.");
            } else {
                emitError("Bir medya zaten gönderiliyor.");
            }
            return;
        }

        MediaMetadata metadata;
        try {
            metadata = readMetadata(uri);
        } catch (IOException exception) {
            emitError("Seçilen dosya okunamadı.");
            return;
        }
        long limit = metadata.mimeType.startsWith("image/")
                ? CryptoBox.MAX_IMAGE_BYTES
                : CryptoBox.MAX_VIDEO_BYTES;
        if (metadata.size <= 0 || metadata.size > limit) {
            emitError(metadata.mimeType.startsWith("image/")
                    ? "Görsel en fazla 8 MB olabilir."
                    : "Video en fazla 20 MB olabilir.");
            return;
        }

        mediaSending.set(true);
        emitTransfer("Medya hazırlanıyor…", true);
        int generation = connectionGeneration;
        mediaExecutor.execute(() -> transferMedia(uri, metadata, generation));
    }

    void leave() {
        connectionGeneration++;
        ChatClient oldClient = chatClient;
        chatClient = null;
        if (oldClient != null) {
            oldClient.close();
        }
        connecting = false;
        connected = false;
        mediaSupported = false;
        presence = 0;
        cryptoBox = null;
        displayName = "";
        roomName = "";
        mediaSending.set(false);
        clearIncomingMedia();
        clearEvents();
        clearDirectory(sessionDirectory);
        stopForeground(STOP_FOREGROUND_REMOVE);
        emitState();
        stopSelf();
    }

    private void beginSession(String server, String room, String secret, String name) {
        if (connecting || connected || chatClient != null) {
            return;
        }
        clearEvents();
        clearIncomingMedia();
        sessionDirectory = new File(getCacheDir(), "session-media");
        clearDirectory(sessionDirectory);
        if (!sessionDirectory.mkdirs() && !sessionDirectory.isDirectory()) {
            failBeforeJoin("Geçici medya alanı hazırlanamadı.");
            return;
        }

        try {
            CryptoBox newCrypto = new CryptoBox(room, secret);
            String roomId = CryptoBox.roomId(room);
            String proof = newCrypto.authProof(roomId);
            cryptoBox = newCrypto;
            displayName = name;
            roomName = room;
            connecting = true;
            connected = false;
            mediaSupported = false;
            presence = 0;
            emitState();

            int generation = ++connectionGeneration;
            chatClient = new ChatClient(server, roomId, proof, new ChatClient.Listener() {
                @Override
                public void onJoined(boolean supportsMedia) {
                    mainHandler.post(() -> {
                        if (generation != connectionGeneration) {
                            return;
                        }
                        connecting = false;
                        connected = true;
                        mediaSupported = supportsMedia;
                        updateForegroundNotification();
                        emitState();
                        if (!supportsMedia) {
                            addSystemEvent(
                                    "Sunucu eski sürümde. Görsel/video için Render servisini " +
                                    "son GitHub commit'iyle yeniden dağıtın."
                            );
                        }
                    });
                }

                @Override
                public void onPresence(int count) {
                    mainHandler.post(() -> {
                        if (generation == connectionGeneration) {
                            presence = count;
                            emitState();
                        }
                    });
                }

                @Override
                public void onCipher(String kind, String payload) {
                    handleCipher(generation, kind, payload);
                }

                @Override
                public void onError(String message) {
                    mainHandler.post(() -> {
                        if (generation == connectionGeneration) {
                            emitError(message);
                        }
                    });
                }

                @Override
                public void onDisconnected() {
                    mainHandler.post(() -> handleDisconnected(generation));
                }
            });
            chatClient.connect();
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            failBeforeJoin("Güvenli bağlantı hazırlanamadı.");
        }
    }

    private void handleCipher(int generation, String kind, String payload) {
        if (generation != connectionGeneration) {
            return;
        }
        CryptoBox currentCrypto = cryptoBox;
        if (currentCrypto == null) {
            return;
        }
        try {
            CryptoBox.DecryptedPacket packet = currentCrypto.decryptPacket(payload);
            if (generation != connectionGeneration) {
                if (packet.data != null) {
                    Arrays.fill(packet.data, (byte) 0);
                }
                return;
            }
            switch (packet.type) {
                case CryptoBox.DecryptedPacket.TEXT:
                    if (!"text".equals(kind)) {
                        return;
                    }
                    mainHandler.post(() -> {
                        if (generation == connectionGeneration) {
                            addEvent(ChatEvent.text(
                                    nextEventId++,
                                    packet.sender,
                                    packet.text,
                                    packet.sentAt,
                                    packet.sender.equals(displayName)
                            ));
                        }
                    });
                    break;
                case CryptoBox.DecryptedPacket.MEDIA_START:
                    if (!"media".equals(kind)) {
                        return;
                    }
                    receiveMediaStart(generation, packet);
                    break;
                case CryptoBox.DecryptedPacket.MEDIA_CHUNK:
                    if (!"media".equals(kind)) {
                        Arrays.fill(packet.data, (byte) 0);
                        return;
                    }
                    receiveMediaChunk(generation, packet);
                    break;
                case CryptoBox.DecryptedPacket.MEDIA_END:
                    if (!"media".equals(kind)) {
                        return;
                    }
                    receiveMediaEnd(generation, packet);
                    break;
                default:
                    throw new GeneralSecurityException("Paket türü desteklenmiyor.");
            }
        } catch (GeneralSecurityException | IOException exception) {
            long now = System.currentTimeMillis();
            if (now - lastDecryptWarningAt > 5_000) {
                lastDecryptWarningAt = now;
                mainHandler.post(() -> {
                    if (generation == connectionGeneration) {
                        addSystemEvent("Açılamayan bir şifreli paket atlandı.");
                    }
                });
            }
        }
    }

    private synchronized void receiveMediaStart(int generation, CryptoBox.DecryptedPacket packet)
            throws IOException {
        if (generation != connectionGeneration || sessionDirectory == null) {
            return;
        }
        pruneIncomingMedia();
        long limit = packet.mimeType.startsWith("image/")
                ? CryptoBox.MAX_IMAGE_BYTES
                : CryptoBox.MAX_VIDEO_BYTES;
        if (packet.size > limit || incomingMedia.size() >= 4) {
            throw new IOException("Medya sınırı aşıldı.");
        }
        IncomingMedia previous = incomingMedia.remove(packet.transferId);
        if (previous != null) {
            previous.abort();
        }
        File partialFile = new File(sessionDirectory, packet.transferId + ".part");
        incomingMedia.put(packet.transferId, new IncomingMedia(packet, partialFile));
    }

    private synchronized void receiveMediaChunk(int generation, CryptoBox.DecryptedPacket packet)
            throws IOException {
        if (generation != connectionGeneration) {
            Arrays.fill(packet.data, (byte) 0);
            return;
        }
        IncomingMedia incoming = incomingMedia.get(packet.transferId);
        try {
            if (incoming == null || packet.index != incoming.nextIndex) {
                throw new IOException("Medya sırası geçersiz.");
            }
            if (incoming.receivedBytes + packet.data.length > incoming.size) {
                throw new IOException("Medya boyutu geçersiz.");
            }
            incoming.output.write(packet.data);
            incoming.digest.update(packet.data);
            incoming.receivedBytes += packet.data.length;
            incoming.nextIndex += 1;
        } catch (IOException exception) {
            abortIncoming(packet.transferId);
            throw exception;
        } finally {
            if (packet.data != null) {
                Arrays.fill(packet.data, (byte) 0);
            }
        }
    }

    private synchronized void receiveMediaEnd(int generation, CryptoBox.DecryptedPacket packet)
            throws IOException {
        if (generation != connectionGeneration) {
            return;
        }
        IncomingMedia incoming = incomingMedia.remove(packet.transferId);
        if (incoming == null) {
            throw new IOException("Medya başlangıcı bulunamadı.");
        }
        incoming.output.close();
        String actualDigest = CryptoBox.toHex(incoming.digest.digest());
        if (
                incoming.receivedBytes != incoming.size ||
                incoming.nextIndex != packet.chunks ||
                !actualDigest.equals(packet.digest)
        ) {
            incoming.partialFile.delete();
            throw new IOException("Medya doğrulanamadı.");
        }
        File completedFile = new File(
                sessionDirectory,
                packet.transferId + extensionFor(incoming.mimeType)
        );
        if (!incoming.partialFile.renameTo(completedFile)) {
            incoming.partialFile.delete();
            throw new IOException("Medya tamamlanamadı.");
        }
        mainHandler.post(() -> {
            if (generation != connectionGeneration) {
                completedFile.delete();
                return;
            }
            addEvent(ChatEvent.media(
                    nextEventId++,
                    incoming.sender,
                    incoming.sentAt,
                    incoming.sender.equals(displayName),
                    completedFile,
                    incoming.mimeType,
                    incoming.displayName,
                    incoming.size
            ));
        });
    }

    private void transferMedia(Uri uri, MediaMetadata metadata, int generation) {
        ChatClient currentClient = chatClient;
        CryptoBox currentCrypto = cryptoBox;
        if (
                generation != connectionGeneration ||
                currentClient == null ||
                currentCrypto == null ||
                !connected
        ) {
            finishTransferWithError(generation, "Bağlantı açık değil.");
            return;
        }
        String transferId = UUID.randomUUID().toString().replace("-", "");
        byte[] buffer = new byte[CryptoBox.MEDIA_CHUNK_BYTES];
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Dosya açılamadı.");
            }
            if (!currentClient.sendCipher("media", currentCrypto.encryptMediaStart(
                    transferId,
                    displayName,
                    metadata.mimeType,
                    metadata.displayName,
                    metadata.size
            ))) {
                throw new IOException("Aktarım başlatılamadı.");
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long sentBytes = 0;
            int chunkIndex = 0;
            int lastPercent = -1;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (!connected || !waitForQueue(currentClient, generation)) {
                    throw new IOException("Bağlantı kesildi.");
                }
                digest.update(buffer, 0, read);
                String encryptedChunk = currentCrypto.encryptMediaChunk(
                        transferId,
                        chunkIndex,
                        buffer,
                        read
                );
                if (!currentClient.sendCipher("media", encryptedChunk)) {
                    throw new IOException("Medya parçası gönderilemedi.");
                }
                sentBytes += read;
                chunkIndex += 1;
                int percent = (int) Math.min(100, (sentBytes * 100L) / metadata.size);
                if (percent >= lastPercent + 5) {
                    lastPercent = percent;
                    emitTransfer("Gönderiliyor: %" + percent, true);
                }
            }
            if (sentBytes != metadata.size || chunkIndex == 0) {
                throw new IOException("Dosya boyutu değişti.");
            }
            if (!waitForQueue(currentClient, generation) || !currentClient.sendCipher(
                    "media",
                    currentCrypto.encryptMediaEnd(
                            transferId,
                            chunkIndex,
                            CryptoBox.toHex(digest.digest())
                    )
            )) {
                throw new IOException("Aktarım tamamlanamadı.");
            }
            if (generation == connectionGeneration) {
                mediaSending.set(false);
                emitTransfer("Medya gönderildi", false);
            }
        } catch (IOException | GeneralSecurityException exception) {
            finishTransferWithError(
                    generation,
                    "Medya gönderilemedi. Bağlantıyı ve dosyayı kontrol edin."
            );
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private boolean waitForQueue(ChatClient client, int generation) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (client.queueSize() > MAX_QUEUE_BYTES) {
            if (
                    generation != connectionGeneration ||
                    !connected ||
                    Thread.currentThread().isInterrupted() ||
                    System.currentTimeMillis() > deadline
            ) {
                return false;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private MediaMetadata readMetadata(Uri uri) throws IOException {
        ContentResolver resolver = getContentResolver();
        String mimeType = resolver.getType(uri);
        if (mimeType == null) {
            throw new IOException("Dosya türü bilinmiyor.");
        }
        mimeType = mimeType.toLowerCase(Locale.ROOT);
        if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) {
            throw new IOException("Dosya türü desteklenmiyor.");
        }

        String displayName = mimeType.startsWith("image/") ? "Görsel" : "Video";
        long size = -1;
        try (Cursor cursor = resolver.query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                    displayName = cursor.getString(nameColumn);
                }
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    size = cursor.getLong(sizeColumn);
                }
            }
        }
        if (size < 0) {
            try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r")) {
                if (descriptor != null) {
                    size = descriptor.getStatSize();
                }
            }
        }
        displayName = displayName.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (displayName.isEmpty()) {
            displayName = mimeType.startsWith("image/") ? "Görsel" : "Video";
        }
        if (displayName.length() > 120) {
            displayName = displayName.substring(0, 120);
        }
        return new MediaMetadata(mimeType, displayName, size);
    }

    private void addEvent(ChatEvent event) {
        if (events.size() >= MAX_EVENTS) {
            ChatEvent removed = events.remove(0);
            if (removed.mediaFile != null) {
                removed.mediaFile.delete();
            }
        }
        events.add(event);
        Listener currentListener = listener;
        if (currentListener != null) {
            currentListener.onEvent(event);
        }
        if (!appVisible && !event.own && event.type != ChatEvent.TYPE_SYSTEM) {
            showMessageNotification();
        }
    }

    private void addSystemEvent(String message) {
        addEvent(ChatEvent.system(nextEventId++, message));
    }

    private void handleDisconnected(int generation) {
        if (generation != connectionGeneration) {
            return;
        }
        boolean hadSession = connecting || connected;
        connecting = false;
        connected = false;
        mediaSupported = false;
        presence = 0;
        chatClient = null;
        cryptoBox = null;
        mediaSending.set(false);
        clearIncomingMedia();
        stopForeground(STOP_FOREGROUND_REMOVE);
        emitState();
        if (hadSession && listener != null) {
            listener.onDisconnected();
        }
        stopSelf();
    }

    private void failBeforeJoin(String message) {
        connecting = false;
        connected = false;
        mediaSupported = false;
        chatClient = null;
        cryptoBox = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        emitError(message);
        emitState();
        stopSelf();
    }

    private void emitState() {
        Listener currentListener = listener;
        if (currentListener != null) {
            currentListener.onSessionState(
                    connecting,
                    connected,
                    roomName,
                    displayName,
                    presence,
                    mediaSupported
            );
        }
    }

    private void emitError(String message) {
        Listener currentListener = listener;
        if (currentListener != null) {
            currentListener.onError(message);
        }
    }

    private void emitTransfer(String status, boolean active) {
        mainHandler.post(() -> {
            Listener currentListener = listener;
            if (currentListener != null) {
                currentListener.onTransferProgress(status, active);
            }
        });
    }

    private void finishTransferWithError(int generation, String message) {
        if (generation != connectionGeneration) {
            return;
        }
        mediaSending.set(false);
        emitTransfer("", false);
        mainHandler.post(() -> emitError(message));
    }

    private synchronized void pruneIncomingMedia() {
        long cutoff = System.currentTimeMillis() - INCOMING_TIMEOUT_MS;
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, IncomingMedia> entry : incomingMedia.entrySet()) {
            if (entry.getValue().createdAt < cutoff) {
                expired.add(entry.getKey());
            }
        }
        for (String id : expired) {
            abortIncoming(id);
        }
    }

    private synchronized void abortIncoming(String transferId) {
        IncomingMedia incoming = incomingMedia.remove(transferId);
        if (incoming != null) {
            incoming.abort();
        }
    }

    private synchronized void clearIncomingMedia() {
        for (IncomingMedia incoming : incomingMedia.values()) {
            incoming.abort();
        }
        incomingMedia.clear();
    }

    private void clearEvents() {
        for (ChatEvent event : events) {
            if (event.mediaFile != null) {
                event.mediaFile.delete();
            }
        }
        events.clear();
        nextEventId = 1;
    }

    private void createNotificationChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel connectionChannel = new NotificationChannel(
                CONNECTION_CHANNEL,
                getString(R.string.notification_channel_connection),
                NotificationManager.IMPORTANCE_LOW
        );
        connectionChannel.setDescription(getString(R.string.notification_channel_connection_description));
        connectionChannel.setShowBadge(false);
        manager.createNotificationChannel(connectionChannel);

        NotificationChannel messageChannel = new NotificationChannel(
                MESSAGE_CHANNEL,
                getString(R.string.notification_channel_messages),
                NotificationManager.IMPORTANCE_HIGH
        );
        messageChannel.setDescription(getString(R.string.notification_channel_messages_description));
        manager.createNotificationChannel(messageChannel);
    }

    private void showForegroundNotification(String status) {
        Notification notification = buildNotification(CONNECTION_CHANNEL, getString(R.string.app_name), status, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    CONNECTION_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            );
        } else {
            startForeground(CONNECTION_NOTIFICATION_ID, notification);
        }
    }

    private void updateForegroundNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(
                    CONNECTION_NOTIFICATION_ID,
                    buildNotification(
                            CONNECTION_CHANNEL,
                            getString(R.string.app_name),
                            getString(R.string.notification_connected),
                            true
                    )
            );
        }
    }

    private void showMessageNotification() {
        if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(
                    MESSAGE_NOTIFICATION_ID,
                    buildNotification(
                            MESSAGE_CHANNEL,
                            getString(R.string.notification_new),
                            null,
                            false
                    )
            );
        }
    }

    private Notification buildNotification(
            String channel,
            String title,
            String text,
            boolean ongoing
    ) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = new Notification.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentIntent(pendingIntent)
                .setAutoCancel(!ongoing)
                .setOngoing(ongoing)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCategory(ongoing ? Notification.CATEGORY_SERVICE : Notification.CATEGORY_MESSAGE);
        if (text != null && !text.isEmpty()) {
            builder.setContentText(text);
        }
        return builder.build();
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

    private static void clearDirectory(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    clearDirectory(child);
                }
                child.delete();
            }
        }
        file.delete();
    }

    @Override
    public void onDestroy() {
        connectionGeneration++;
        ChatClient oldClient = chatClient;
        chatClient = null;
        if (oldClient != null) {
            oldClient.close();
        }
        mediaExecutor.shutdownNow();
        clearIncomingMedia();
        clearEvents();
        clearDirectory(sessionDirectory);
        cryptoBox = null;
        listener = null;
        super.onDestroy();
    }

    private static final class MediaMetadata {
        final String mimeType;
        final String displayName;
        final long size;

        MediaMetadata(String mimeType, String displayName, long size) {
            this.mimeType = mimeType;
            this.displayName = displayName;
            this.size = size;
        }
    }

    private static final class IncomingMedia {
        final String sender;
        final String mimeType;
        final String displayName;
        final long size;
        final long sentAt;
        final long createdAt;
        final File partialFile;
        final FileOutputStream output;
        final MessageDigest digest;
        long receivedBytes;
        int nextIndex;

        IncomingMedia(CryptoBox.DecryptedPacket packet, File partialFile) throws IOException {
            this.sender = packet.sender;
            this.mimeType = packet.mimeType;
            this.displayName = packet.displayName;
            this.size = packet.size;
            this.sentAt = packet.sentAt;
            this.createdAt = System.currentTimeMillis();
            this.partialFile = partialFile;
            this.output = new FileOutputStream(partialFile, false);
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (GeneralSecurityException exception) {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
                partialFile.delete();
                throw new IOException("Doğrulama hazırlanamadı.", exception);
            }
        }

        void abort() {
            try {
                output.close();
            } catch (IOException ignored) {
            }
            partialFile.delete();
        }
    }
}

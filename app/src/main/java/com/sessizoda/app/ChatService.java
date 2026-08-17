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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChatService extends Service {
    static final String ACTION_CONNECT = "com.sessizoda.app.CONNECT";
    static final String ACTION_MONITOR = "com.sessizoda.app.MONITOR";
    static final String EXTRA_SERVER = "server";
    static final String EXTRA_ROOM = "room";
    static final String EXTRA_SECRET = "secret";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_RETENTION_MS = "retention_ms";

    private static final String CONNECTION_CHANNEL = "connection";
    private static final String MESSAGE_CHANNEL = "messages";
    private static final int CONNECTION_NOTIFICATION_ID = 1001;
    private static final int MESSAGE_NOTIFICATION_ID = 2_000;
    private static final int MAX_EVENTS = 150;
    private static final long MAX_QUEUE_BYTES = 16L * 1024L;
    private static final long INCOMING_TIMEOUT_MS = 6L * 60L * 60L * 1_000L;
    private static final long MONITOR_RETRY_MIN_MS = 5_000L;
    private static final long MONITOR_RETRY_MAX_MS = 5L * 60L * 1_000L;

    interface Listener {
        void onSessionState(
                boolean connecting,
                boolean connected,
                String roomName,
                String displayName,
                int presence,
                boolean mediaSupported,
                boolean advancedSupported,
                long retentionMs
        );

        void onEvent(ChatEvent event);

        void onHistoryReset(List<ChatEvent> events);

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
    private final ExecutorService storageExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean mediaSending = new AtomicBoolean(false);
    private final List<ChatEvent> events = new ArrayList<>();
    private final Map<String, IncomingMedia> incomingMedia = new HashMap<>();
    private final Set<String> ignoredIncomingMedia = new HashSet<>();
    private final Map<String, ReceiptTracker> receiptTrackers = new HashMap<>();
    private final Map<String, RoomMonitorClient> monitorClients = new HashMap<>();
    private final Runnable historyExpiryRunnable = this::handleHistoryExpiry;
    private final Runnable monitorRetryRunnable = this::reloadNotificationMonitors;

    private Listener listener;
    private LocalStore localStore;
    private volatile ChatClient chatClient;
    private volatile CryptoBox cryptoBox;
    private volatile File sessionDirectory;
    private volatile String displayName = "";
    private volatile String localClientId = "";
    private String roomName = "";
    private String roomKey = "";
    private String activeServer = "";
    private String activeRoomId = "";
    private boolean appVisible;
    private boolean monitoringRequested;
    private boolean monitorLoadRunning;
    private boolean foregroundActive;
    private boolean destroyed;
    private boolean monitorRetryScheduled;
    private volatile boolean connecting;
    private volatile boolean connected;
    private volatile boolean mediaSupported;
    private volatile boolean advancedSupported;
    private int presence;
    private volatile int connectionGeneration;
    private long retentionMs = RetentionPolicy.DEFAULT_MS;
    private long historyExpiresAt;
    private long historyCycle;
    private long nextEventId = 1;
    private long lastDecryptWarningAt;
    private long lastStoreWarningAt;
    private long monitorGeneration;
    private long monitorRetryDelayMs = MONITOR_RETRY_MIN_MS;

    @Override
    public void onCreate() {
        super.onCreate();
        localStore = LocalStore.get(this);
        createNotificationChannels();
        clearSessionDirectories();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_MONITOR : intent.getAction();
        if (ACTION_MONITOR.equals(action)) {
            monitoringRequested = canPostNotifications();
            if (monitoringRequested) {
                showForegroundNotification(getString(R.string.notification_monitoring));
                reloadNotificationMonitors();
                return START_STICKY;
            }
            updatePersistentNotification();
            return START_NOT_STICKY;
        }
        if (!ACTION_CONNECT.equals(action)) {
            return START_NOT_STICKY;
        }

        monitoringRequested = canPostNotifications();
        showForegroundNotification(getString(R.string.status_connecting));
        String server = intent.getStringExtra(EXTRA_SERVER);
        String room = intent.getStringExtra(EXTRA_ROOM);
        String secret = intent.getStringExtra(EXTRA_SECRET);
        String name = intent.getStringExtra(EXTRA_NAME);
        long requestedRetention = intent.getLongExtra(
                EXTRA_RETENTION_MS,
                RetentionPolicy.DEFAULT_MS
        );
        intent.removeExtra(EXTRA_SECRET);
        if (
                server == null ||
                room == null ||
                secret == null ||
                name == null ||
                !RetentionPolicy.isSupported(requestedRetention)
        ) {
            failBeforeJoin("Bağlantı bilgileri eksik.");
            return START_STICKY;
        }
        beginSession(server, room, secret, name, requestedRetention);
        return START_STICKY;
    }

    void setListener(Listener newListener, long afterEventId) {
        listener = newListener;
        if (newListener == null) {
            return;
        }
        emitState();
        List<ChatEvent> snapshot = new ArrayList<>(events);
        if (afterEventId <= 0) {
            newListener.onHistoryReset(snapshot);
        } else {
            for (ChatEvent event : snapshot) {
                if (event.id > afterEventId) {
                    newListener.onEvent(event);
                }
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
            sendPendingSeenReceipts();
        }
    }

    boolean isConnected() {
        return connected;
    }

    boolean sendText(String message, ChatEvent.Reply reply) {
        ChatClient currentClient = chatClient;
        CryptoBox currentCrypto = cryptoBox;
        String currentClientId = localClientId;
        if (
                !connected ||
                currentClient == null ||
                currentCrypto == null ||
                currentClientId.isEmpty()
        ) {
            emitError("Bağlantı açık değil.");
            return false;
        }
        String messageId = UUID.randomUUID().toString().replace("-", "");
        long sentAt = System.currentTimeMillis();
        try {
            String payload = currentCrypto.encryptText(
                    messageId,
                    currentClientId,
                    displayName,
                    message,
                    sentAt,
                    reply
            );
            if (!currentClient.sendCipher(
                    "text",
                    payload,
                    advancedSupported ? messageId : null
            )) {
                emitError("Mesaj gönderilemedi.");
                return false;
            }
            addEvent(ChatEvent.text(
                    nextEventId++,
                    messageId,
                    displayName,
                    message,
                    sentAt,
                    true,
                    reply,
                    advancedSupported ? ChatEvent.STATUS_PENDING : ChatEvent.STATUS_NONE
            ));
            return true;
        } catch (GeneralSecurityException exception) {
            emitError("Mesaj şifrelenemedi.");
            return false;
        }
    }

    boolean sendMedia(Uri uri, boolean viewOnce, ChatEvent.Reply reply) {
        if (!connected || !mediaSupported || !mediaSending.compareAndSet(false, true)) {
            if (!connected) {
                emitError("Bağlantı açık değil.");
            } else if (!mediaSupported) {
                emitError("Sunucu medya desteği için güncellenmemiş.");
            } else {
                emitError("Bir medya zaten gönderiliyor.");
            }
            return false;
        }
        if (viewOnce && !advancedSupported) {
            mediaSending.set(false);
            emitError("Tek gösterimlik medya için Render sunucusunu v1.5 sürümüne güncelleyin.");
            return false;
        }

        MediaMetadata metadata;
        try {
            metadata = readMetadata(uri);
        } catch (IOException exception) {
            mediaSending.set(false);
            emitError("Seçilen dosya okunamadı.");
            return false;
        }
        long limit = metadata.mimeType.startsWith("image/")
                ? CryptoBox.MAX_IMAGE_BYTES
                : CryptoBox.MAX_VIDEO_BYTES;
        if (metadata.size <= 0 || metadata.size > limit) {
            mediaSending.set(false);
            emitError(metadata.mimeType.startsWith("image/")
                    ? "Görsel en fazla 100 MB olabilir."
                    : "Video en fazla 500 MB olabilir.");
            return false;
        }

        emitTransfer("Medya hazırlanıyor…", true);
        int generation = connectionGeneration;
        mediaExecutor.execute(() -> transferMedia(
                uri,
                metadata,
                generation,
                viewOnce,
                reply
        ));
        return true;
    }

    boolean claimViewOnce(String messageId) {
        ChatEvent event = findEvent(messageId);
        if (
                event == null ||
                event.own ||
                event.type != ChatEvent.TYPE_MEDIA ||
                !event.viewOnce ||
                event.viewOnceConsumed ||
                event.mediaFile == null ||
                !event.mediaFile.isFile()
        ) {
            return false;
        }
        event.viewOnceConsumed = true;
        sendReceipt(event, "seen");
        notifyHistoryChanged();
        return true;
    }

    void finishViewOnce(String messageId) {
        ChatEvent event = findEvent(messageId);
        if (
                event != null &&
                event.viewOnce &&
                event.viewOnceConsumed &&
                event.mediaFile != null
        ) {
            event.mediaFile.delete();
        }
    }

    void leave() {
        connectionGeneration++;
        historyCycle++;
        mainHandler.removeCallbacks(historyExpiryRunnable);
        ChatClient oldClient = chatClient;
        chatClient = null;
        if (oldClient != null) {
            oldClient.close();
        }
        File oldSessionDirectory = sessionDirectory;
        sessionDirectory = null;
        events.clear();
        nextEventId = 1;
        connecting = false;
        connected = false;
        mediaSupported = false;
        advancedSupported = false;
        presence = 0;
        cryptoBox = null;
        displayName = "";
        localClientId = "";
        roomName = "";
        roomKey = "";
        activeServer = "";
        activeRoomId = "";
        retentionMs = RetentionPolicy.DEFAULT_MS;
        historyExpiresAt = 0;
        mediaSending.set(false);
        clearIncomingMedia();
        receiptTrackers.clear();
        storageExecutor.execute(() -> clearDirectory(oldSessionDirectory));
        emitState();
        reloadNotificationMonitors();
    }

    private void beginSession(
            String server,
            String room,
            String secret,
            String name,
            long requestedRetention
    ) {
        if (connecting || connected || chatClient != null) {
            return;
        }
        events.clear();
        nextEventId = 1;
        clearIncomingMedia();
        receiptTrackers.clear();
        int generation = ++connectionGeneration;
        historyCycle++;
        File newSessionDirectory = new File(
                getCacheDir(),
                "session-media-" + generation + "-" + System.currentTimeMillis()
        );
        sessionDirectory = newSessionDirectory;
        if (!sessionDirectory.mkdirs() && !sessionDirectory.isDirectory()) {
            failBeforeJoin("Geçici medya alanı hazırlanamadı.");
            return;
        }

        try {
            CryptoBox newCrypto = new CryptoBox(room, secret);
            String roomId = CryptoBox.roomId(room);
            String proof = newCrypto.authProof(roomId);
            long sessionRetention = RetentionPolicy.normalize(requestedRetention);
            cryptoBox = newCrypto;
            displayName = name;
            roomName = room;
            activeServer = server;
            activeRoomId = roomId;
            retentionMs = sessionRetention;
            historyExpiresAt = 0;
            connecting = true;
            connected = false;
            mediaSupported = false;
            advancedSupported = false;
            presence = 0;
            emitState();

            storageExecutor.execute(() -> {
                try {
                    LocalStore.RoomHistory history = localStore.prepareRoom(
                            server,
                            room,
                            name,
                            roomId,
                            proof,
                            sessionRetention,
                            newSessionDirectory
                    );
                    mainHandler.post(() -> {
                        if (generation != connectionGeneration) {
                            clearDirectory(newSessionDirectory);
                            return;
                        }
                        roomKey = history.roomKey;
                        localClientId = history.clientId;
                        historyExpiresAt = history.expiresAt;
                        replaceHistory(history.events);
                        openSocket(generation, server, roomId, proof, history.clientId);
                        reloadNotificationMonitors();
                    });
                } catch (IOException | GeneralSecurityException exception) {
                    mainHandler.post(() -> {
                        if (generation == connectionGeneration) {
                            failBeforeJoin("Şifreli yerel geçmiş hazırlanamadı.");
                        }
                    });
                }
            });
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            failBeforeJoin("Güvenli bağlantı hazırlanamadı.");
        }
    }

    private void openSocket(
            int generation,
            String server,
            String roomId,
            String proof,
            String clientId
    ) {
        try {
            chatClient = new ChatClient(server, roomId, proof, clientId, new ChatClient.Listener() {
                @Override
                public void onJoined(
                        boolean supportsMedia,
                        boolean supportsNotifications,
                        boolean supportsAdvanced
                ) {
                    mainHandler.post(() -> {
                        if (generation != connectionGeneration) {
                            return;
                        }
                        connecting = false;
                        connected = true;
                        mediaSupported = supportsMedia;
                        advancedSupported = supportsAdvanced;
                        updatePersistentNotification();
                        reloadNotificationMonitors();
                        scheduleHistoryExpiry();
                        emitState();
                        if (!supportsMedia) {
                            addSystemEvent(
                                    "Sunucu eski sürümde. Görsel/video için Render servisini " +
                                    "son GitHub commit'iyle yeniden dağıtın."
                            );
                        }
                        if (!supportsNotifications) {
                            addSystemEvent(
                                    "Sunucu arka plan bildirimlerini desteklemiyor. Render " +
                                    "servisini son GitHub commit'iyle yeniden dağıtın."
                            );
                        }
                        if (!supportsAdvanced) {
                            addSystemEvent(
                                    "Yanıtlama, tek gösterim ve teslim/görüldü bilgisi için " +
                                    "Render servisini v1.5 sunucusuyla yeniden dağıtın."
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
                public void onAccepted(String messageId, int recipients) {
                    mainHandler.post(() -> {
                        if (generation == connectionGeneration) {
                            handleAccepted(messageId, recipients);
                        }
                    });
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
        } catch (IllegalArgumentException exception) {
            failBeforeJoin("Bağlantı adresi açılamadı.");
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
                    if (
                            packet.deviceId != null &&
                            packet.deviceId.equals(localClientId)
                    ) {
                        return;
                    }
                    mainHandler.post(() -> {
                        if (generation == connectionGeneration) {
                            boolean own =
                                    packet.deviceId == null &&
                                    packet.sender.equals(displayName);
                            ChatEvent event = ChatEvent.text(
                                    nextEventId++,
                                    packet.messageId,
                                    packet.sender,
                                    packet.text,
                                    packet.sentAt,
                                    own,
                                    packet.reply,
                                    ChatEvent.STATUS_NONE
                            );
                            addEvent(event);
                            sendReceivedReceipts(event);
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
                case CryptoBox.DecryptedPacket.RECEIPT:
                    if (!"receipt".equals(kind)) {
                        return;
                    }
                    mainHandler.post(() -> {
                        if (generation == connectionGeneration) {
                            handleReceipt(packet);
                        }
                    });
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
        if (packet.deviceId != null && packet.deviceId.equals(localClientId)) {
            ignoredIncomingMedia.add(packet.transferId);
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
        if (ignoredIncomingMedia.contains(packet.transferId)) {
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
        if (ignoredIncomingMedia.remove(packet.transferId)) {
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
            ChatEvent event = ChatEvent.media(
                    nextEventId++,
                    incoming.messageId,
                    incoming.sender,
                    incoming.sentAt,
                    false,
                    completedFile,
                    incoming.mimeType,
                    incoming.displayName,
                    incoming.size,
                    incoming.reply,
                    incoming.viewOnce,
                    false,
                    ChatEvent.STATUS_NONE
            );
            addEvent(event);
            sendReceivedReceipts(event);
        });
    }

    private void transferMedia(
            Uri uri,
            MediaMetadata metadata,
            int generation,
            boolean viewOnce,
            ChatEvent.Reply reply
    ) {
        ChatClient currentClient = chatClient;
        CryptoBox currentCrypto = cryptoBox;
        String currentClientId = localClientId;
        if (
                generation != connectionGeneration ||
                currentClient == null ||
                currentCrypto == null ||
                currentClientId.isEmpty() ||
                !connected
        ) {
            finishTransferWithError(generation, "Bağlantı açık değil.");
            return;
        }
        String transferId = UUID.randomUUID().toString().replace("-", "");
        long sentAt = System.currentTimeMillis();
        byte[] buffer = new byte[CryptoBox.MEDIA_CHUNK_BYTES];
        File localPart = viewOnce || sessionDirectory == null
                ? null
                : new File(sessionDirectory, transferId + ".sending");
        File completedFile = viewOnce || sessionDirectory == null
                ? null
                : new File(sessionDirectory, transferId + extensionFor(metadata.mimeType));
        FileOutputStream localOutput = null;
        boolean completed = false;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Dosya açılamadı.");
            }
            if (localPart != null) {
                localOutput = new FileOutputStream(localPart, false);
            }
            if (!currentClient.sendMediaCipher("start", currentCrypto.encryptMediaStart(
                    transferId,
                    currentClientId,
                    displayName,
                    metadata.mimeType,
                    metadata.displayName,
                    metadata.size,
                    sentAt,
                    viewOnce,
                    reply
            ), advancedSupported ? transferId : null)) {
                throw new IOException("Aktarım başlatılamadı.");
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long sentBytes = 0;
            int chunkIndex = 0;
            int lastPercent = -1;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                if (!connected || !waitForQueue(currentClient, generation)) {
                    throw new IOException("Bağlantı kesildi.");
                }
                digest.update(buffer, 0, read);
                if (localOutput != null) {
                    localOutput.write(buffer, 0, read);
                }
                String encryptedChunk = currentCrypto.encryptMediaChunk(
                        transferId,
                        chunkIndex,
                        buffer,
                        read
                );
                if (!currentClient.sendMediaCipher(
                        "chunk",
                        encryptedChunk,
                        advancedSupported ? transferId : null
                )) {
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
            if (!waitForQueue(currentClient, generation) || !currentClient.sendMediaCipher(
                    "end",
                    currentCrypto.encryptMediaEnd(
                            transferId,
                            chunkIndex,
                            CryptoBox.toHex(digest.digest())
                    ),
                    advancedSupported ? transferId : null
            )) {
                throw new IOException("Aktarım tamamlanamadı.");
            }
            if (localOutput != null) {
                localOutput.flush();
                localOutput.getFD().sync();
                localOutput.close();
                localOutput = null;
                if (!localPart.renameTo(completedFile)) {
                    throw new IOException("Yerel medya tamamlanamadı.");
                }
            }
            if (generation == connectionGeneration) {
                File eventFile = completedFile;
                mainHandler.post(() -> {
                    if (generation != connectionGeneration) {
                        if (eventFile != null) {
                            eventFile.delete();
                        }
                        return;
                    }
                    addEvent(ChatEvent.media(
                            nextEventId++,
                            transferId,
                            displayName,
                            sentAt,
                            true,
                            eventFile,
                            metadata.mimeType,
                            metadata.displayName,
                            metadata.size,
                            reply,
                            viewOnce,
                            viewOnce,
                            advancedSupported
                                    ? ChatEvent.STATUS_PENDING
                                    : ChatEvent.STATUS_NONE
                    ));
                });
                mediaSending.set(false);
                emitTransfer("Medya gönderildi", false);
            }
            completed = true;
        } catch (IOException | GeneralSecurityException exception) {
            finishTransferWithError(
                    generation,
                    "Medya gönderilemedi. Bağlantıyı ve dosyayı kontrol edin."
            );
        } finally {
            if (localOutput != null) {
                try {
                    localOutput.close();
                } catch (IOException ignored) {
                }
            }
            if (!completed) {
                if (localPart != null) {
                    localPart.delete();
                }
                if (completedFile != null) {
                    completedFile.delete();
                }
            }
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private boolean waitForQueue(ChatClient client, int generation) {
        while (client.queueSize() > MAX_QUEUE_BYTES) {
            if (
                    generation != connectionGeneration ||
                    !connected ||
                    Thread.currentThread().isInterrupted()
            ) {
                return false;
            }
            try {
                Thread.sleep(10);
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

    private void handleAccepted(String messageId, int recipients) {
        ReceiptTracker tracker = receiptTrackers.computeIfAbsent(
                messageId,
                ignored -> new ReceiptTracker()
        );
        tracker.expectedRecipients = recipients;
        ChatEvent event = findEvent(messageId);
        if (event != null && event.own) {
            applyReceiptTracker(event, tracker);
        }
    }

    private void handleReceipt(CryptoBox.DecryptedPacket packet) {
        if (packet.deviceId == null || packet.deviceId.equals(localClientId)) {
            return;
        }
        ChatEvent event = findEvent(packet.messageId);
        ReceiptTracker tracker = receiptTrackers.get(packet.messageId);
        if (tracker == null) {
            if (event == null || !event.own) {
                return;
            }
            tracker = new ReceiptTracker();
            receiptTrackers.put(packet.messageId, tracker);
        }
        tracker.deliveredBy.add(packet.deviceId);
        if ("seen".equals(packet.receiptState)) {
            tracker.seenBy.add(packet.deviceId);
        }
        if (event != null && event.own) {
            applyReceiptTracker(event, tracker);
        }
    }

    private void applyReceiptTracker(ChatEvent event, ReceiptTracker tracker) {
        int updatedStatus = event.deliveryStatus;
        event.expectedRecipients = tracker.expectedRecipients;
        event.deliveredBy.addAll(tracker.deliveredBy);
        event.seenBy.addAll(tracker.seenBy);
        if (tracker.expectedRecipients >= 0) {
            updatedStatus = Math.max(updatedStatus, ChatEvent.STATUS_SENT);
        }
        if (
                tracker.expectedRecipients > 0 &&
                tracker.deliveredBy.size() >= tracker.expectedRecipients
        ) {
            updatedStatus = Math.max(updatedStatus, ChatEvent.STATUS_DELIVERED);
        }
        if (
                tracker.expectedRecipients > 0 &&
                tracker.seenBy.size() >= tracker.expectedRecipients
        ) {
            updatedStatus = ChatEvent.STATUS_SEEN;
        }
        if (updatedStatus == event.deliveryStatus) {
            return;
        }
        event.deliveryStatus = updatedStatus;
        persistDeliveryStatus(event);
        notifyHistoryChanged();
    }

    private void sendReceivedReceipts(ChatEvent event) {
        if (
                event == null ||
                event.own ||
                event.messageId == null ||
                !advancedSupported
        ) {
            return;
        }
        sendReceipt(event, "delivered");
        if (appVisible && !event.viewOnce) {
            sendReceipt(event, "seen");
        }
    }

    private void sendPendingSeenReceipts() {
        if (!connected || !advancedSupported) {
            return;
        }
        for (ChatEvent event : new ArrayList<>(events)) {
            if (
                    !event.own &&
                    event.messageId != null &&
                    !event.readReceiptSent &&
                    (!event.viewOnce || event.viewOnceConsumed)
            ) {
                sendReceipt(event, "seen");
            }
        }
    }

    private void sendReceipt(ChatEvent event, String state) {
        ChatClient currentClient = chatClient;
        CryptoBox currentCrypto = cryptoBox;
        if (
                !connected ||
                !advancedSupported ||
                currentClient == null ||
                currentCrypto == null ||
                event.messageId == null ||
                localClientId.isEmpty()
        ) {
            return;
        }
        try {
            boolean sent = currentClient.sendReceiptCipher(currentCrypto.encryptReceipt(
                    event.messageId,
                    localClientId,
                    state
            ));
            if (sent && "seen".equals(state)) {
                event.readReceiptSent = true;
            }
        } catch (GeneralSecurityException ignored) {
        }
    }

    private ChatEvent findEvent(String messageId) {
        if (messageId == null) {
            return null;
        }
        for (ChatEvent event : events) {
            if (messageId.equals(event.messageId)) {
                return event;
            }
        }
        return null;
    }

    private void persistDeliveryStatus(ChatEvent event) {
        if (
                event.messageId == null ||
                event.viewOnce ||
                roomKey.isEmpty() ||
                event.deliveryStatus < ChatEvent.STATUS_SENT
        ) {
            return;
        }
        String activeRoomKey = roomKey;
        String messageId = event.messageId;
        int status = event.deliveryStatus;
        int generation = connectionGeneration;
        storageExecutor.execute(() -> {
            try {
                localStore.updateEventStatus(activeRoomKey, messageId, status);
            } catch (IOException | GeneralSecurityException exception) {
                emitStoreWarning(generation);
            }
        });
    }

    private void notifyHistoryChanged() {
        Listener currentListener = listener;
        if (currentListener != null) {
            currentListener.onHistoryReset(new ArrayList<>(events));
        }
    }

    private void addEvent(ChatEvent event) {
        if (event.type != ChatEvent.TYPE_SYSTEM) {
            expireHistoryIfNeeded();
            if (historyExpiresAt <= 0) {
                historyExpiresAt = System.currentTimeMillis() + retentionMs;
                scheduleHistoryExpiry();
            }
        }
        ChatEvent removed = null;
        if (events.size() >= MAX_EVENTS) {
            removed = events.remove(0);
            if (removed.messageId != null) {
                receiptTrackers.remove(removed.messageId);
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
        if (event.own && event.messageId != null) {
            ReceiptTracker tracker = receiptTrackers.get(event.messageId);
            if (tracker != null) {
                applyReceiptTracker(event, tracker);
            }
        }
        ChatEvent removedEvent = removed;
        String activeRoomKey = roomKey;
        int generation = connectionGeneration;
        long eventHistoryCycle = historyCycle;
        if (
                event.type != ChatEvent.TYPE_SYSTEM &&
                !event.viewOnce &&
                !activeRoomKey.isEmpty()
        ) {
            storageExecutor.execute(() -> {
                try {
                    long expiresAt = localStore.appendEvent(activeRoomKey, event);
                    mainHandler.post(() -> {
                        if (
                                generation == connectionGeneration &&
                                activeRoomKey.equals(roomKey) &&
                                eventHistoryCycle == historyCycle &&
                                expiresAt > 0
                        ) {
                            historyExpiresAt = expiresAt;
                            scheduleHistoryExpiry();
                        }
                    });
                } catch (IOException | GeneralSecurityException exception) {
                    emitStoreWarning(generation);
                } finally {
                    deleteEventFile(removedEvent);
                }
            });
        } else {
            deleteEventFile(removedEvent);
        }
    }

    private void addSystemEvent(String message) {
        addEvent(ChatEvent.system(nextEventId++, message));
    }

    private void replaceHistory(List<ChatEvent> history) {
        events.clear();
        long highestId = 0;
        for (ChatEvent event : history) {
            events.add(event);
            highestId = Math.max(highestId, event.id);
        }
        nextEventId = highestId + 1;
        Listener currentListener = listener;
        if (currentListener != null) {
            currentListener.onHistoryReset(new ArrayList<>(events));
        }
    }

    private void expireHistoryIfNeeded() {
        if (historyExpiresAt > 0 && System.currentTimeMillis() >= historyExpiresAt) {
            expireHistoryNow();
        }
    }

    private void handleHistoryExpiry() {
        if (historyExpiresAt <= 0) {
            return;
        }
        if (System.currentTimeMillis() < historyExpiresAt) {
            scheduleHistoryExpiry();
            return;
        }
        expireHistoryNow();
    }

    private void expireHistoryNow() {
        mainHandler.removeCallbacks(historyExpiryRunnable);
        historyCycle++;
        String expiredRoomKey = roomKey;
        int generation = connectionGeneration;
        List<ChatEvent> expiredEvents = new ArrayList<>(events);
        events.clear();
        receiptTrackers.clear();
        historyExpiresAt = 0;
        Listener currentListener = listener;
        if (currentListener != null) {
            currentListener.onHistoryReset(new ArrayList<>());
        }
        if (!expiredRoomKey.isEmpty()) {
            storageExecutor.execute(() -> {
                try {
                    localStore.clearHistory(expiredRoomKey);
                } catch (IOException | GeneralSecurityException exception) {
                    emitStoreWarning(generation);
                } finally {
                    for (ChatEvent event : expiredEvents) {
                        deleteEventFile(event);
                    }
                }
            });
        } else {
            for (ChatEvent event : expiredEvents) {
                deleteEventFile(event);
            }
        }
    }

    private void scheduleHistoryExpiry() {
        mainHandler.removeCallbacks(historyExpiryRunnable);
        if (historyExpiresAt <= 0) {
            return;
        }
        long delay = Math.max(1_000L, historyExpiresAt - System.currentTimeMillis());
        mainHandler.postDelayed(historyExpiryRunnable, delay);
    }

    private void emitStoreWarning(int generation) {
        mainHandler.post(() -> {
            if (generation != connectionGeneration) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastStoreWarningAt > 30_000L) {
                lastStoreWarningAt = now;
                emitError("Yerel sohbet geçmişi kaydedilemedi.");
            }
        });
    }

    private static void deleteEventFile(ChatEvent event) {
        if (event != null && event.mediaFile != null) {
            event.mediaFile.delete();
        }
    }

    private void handleDisconnected(int generation) {
        if (generation != connectionGeneration) {
            return;
        }
        boolean hadSession = connecting || connected;
        connecting = false;
        connected = false;
        mediaSupported = false;
        advancedSupported = false;
        presence = 0;
        chatClient = null;
        cryptoBox = null;
        localClientId = "";
        activeServer = "";
        activeRoomId = "";
        mediaSending.set(false);
        clearIncomingMedia();
        emitState();
        if (hadSession && listener != null) {
            listener.onDisconnected();
        }
        reloadNotificationMonitors();
    }

    private void failBeforeJoin(String message) {
        connectionGeneration++;
        historyCycle++;
        mainHandler.removeCallbacks(historyExpiryRunnable);
        ChatClient failedClient = chatClient;
        chatClient = null;
        if (failedClient != null) {
            failedClient.close();
        }
        File failedSessionDirectory = sessionDirectory;
        sessionDirectory = null;
        connecting = false;
        connected = false;
        mediaSupported = false;
        advancedSupported = false;
        cryptoBox = null;
        localClientId = "";
        activeServer = "";
        activeRoomId = "";
        historyExpiresAt = 0;
        storageExecutor.execute(() -> clearDirectory(failedSessionDirectory));
        emitError(message);
        emitState();
        reloadNotificationMonitors();
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
                    mediaSupported,
                    advancedSupported,
                    retentionMs
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
        ignoredIncomingMedia.clear();
    }

    private void reloadNotificationMonitors() {
        mainHandler.removeCallbacks(monitorRetryRunnable);
        monitorRetryScheduled = false;
        if (destroyed) {
            return;
        }
        if (!monitoringRequested || !canPostNotifications()) {
            monitorGeneration++;
            monitorLoadRunning = false;
            closeMonitorClients();
            updatePersistentNotification();
            return;
        }

        long requestGeneration = ++monitorGeneration;
        monitorLoadRunning = true;
        updatePersistentNotification();
        storageExecutor.execute(() -> {
            try {
                LocalStore.NotificationSnapshot snapshot = localStore.getNotificationSnapshot();
                mainHandler.post(() -> applyNotificationSnapshot(requestGeneration, snapshot));
            } catch (IOException | GeneralSecurityException exception) {
                mainHandler.post(() -> {
                    if (destroyed || requestGeneration != monitorGeneration) {
                        return;
                    }
                    monitorLoadRunning = false;
                    scheduleMonitorRefresh();
                    updatePersistentNotification();
                });
            }
        });
    }

    private void applyNotificationSnapshot(
            long requestGeneration,
            LocalStore.NotificationSnapshot snapshot
    ) {
        if (destroyed || requestGeneration != monitorGeneration) {
            return;
        }
        monitorLoadRunning = false;
        closeMonitorClients();

        Map<String, List<LocalStore.NotificationRoom>> roomsByServer = new HashMap<>();
        for (LocalStore.NotificationRoom room : snapshot.rooms) {
            if (
                    (connecting || connected) &&
                    room.server.equals(activeServer) &&
                    room.roomId.equals(activeRoomId)
            ) {
                continue;
            }
            roomsByServer.computeIfAbsent(room.server, ignored -> new ArrayList<>()).add(room);
        }

        if (snapshot.rooms.isEmpty()) {
            monitoringRequested = false;
        }
        monitorRetryDelayMs = MONITOR_RETRY_MIN_MS;
        for (Map.Entry<String, List<LocalStore.NotificationRoom>> entry : roomsByServer.entrySet()) {
            String server = entry.getKey();
            List<LocalStore.NotificationRoom> rooms = entry.getValue();
            RoomMonitorClient[] holder = new RoomMonitorClient[1];
            RoomMonitorClient monitor = new RoomMonitorClient(
                    server,
                    snapshot.clientId,
                    rooms,
                    new RoomMonitorClient.Listener() {
                        @Override
                        public void onReady() {
                            mainHandler.post(() -> {
                                if (
                                        !destroyed &&
                                        requestGeneration == monitorGeneration &&
                                        monitorClients.get(server) == holder[0]
                                ) {
                                    monitorRetryDelayMs = MONITOR_RETRY_MIN_MS;
                                    updatePersistentNotification();
                                }
                            });
                        }

                        @Override
                        public void onActivity(String roomId) {
                            mainHandler.post(() -> {
                                if (
                                        !destroyed &&
                                        requestGeneration == monitorGeneration &&
                                        monitorClients.get(server) == holder[0] &&
                                        !appVisible
                                ) {
                                    showMessageNotification();
                                }
                            });
                        }

                        @Override
                        public void onDisconnected() {
                            mainHandler.post(() -> {
                                if (
                                        destroyed ||
                                        requestGeneration != monitorGeneration ||
                                        monitorClients.get(server) != holder[0]
                                ) {
                                    return;
                                }
                                monitorClients.remove(server);
                                scheduleMonitorRefresh();
                                updatePersistentNotification();
                            });
                        }
                    }
            );
            holder[0] = monitor;
            monitorClients.put(server, monitor);
            monitor.connect();
        }
        updatePersistentNotification();
    }

    private void scheduleMonitorRefresh() {
        if (destroyed || !monitoringRequested || !canPostNotifications()) {
            return;
        }
        mainHandler.removeCallbacks(monitorRetryRunnable);
        monitorRetryScheduled = true;
        mainHandler.postDelayed(monitorRetryRunnable, monitorRetryDelayMs);
        monitorRetryDelayMs = Math.min(MONITOR_RETRY_MAX_MS, monitorRetryDelayMs * 2L);
    }

    private void closeMonitorClients() {
        List<RoomMonitorClient> clients = new ArrayList<>(monitorClients.values());
        monitorClients.clear();
        for (RoomMonitorClient client : clients) {
            client.close();
        }
    }

    private boolean canPostNotifications() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED;
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
        foregroundActive = true;
    }

    private void updatePersistentNotification() {
        String status = null;
        if (connecting) {
            status = getString(R.string.status_connecting);
        } else if (connected) {
            status = getString(R.string.notification_connected);
        } else if (
                monitorLoadRunning ||
                monitorRetryScheduled ||
                !monitorClients.isEmpty()
        ) {
            status = getString(R.string.notification_monitoring);
        }

        if (status != null) {
            if (!foregroundActive) {
                showForegroundNotification(status);
                return;
            }
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(
                        CONNECTION_NOTIFICATION_ID,
                        buildNotification(
                                CONNECTION_CHANNEL,
                                getString(R.string.app_name),
                                status,
                                true
                        )
                );
            }
            return;
        }
        if (foregroundActive) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundActive = false;
        }
        stopSelf();
    }

    private void showMessageNotification() {
        if (!canPostNotifications()) {
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

    private void clearSessionDirectories() {
        File[] cacheFiles = getCacheDir().listFiles();
        if (cacheFiles == null) {
            return;
        }
        for (File file : cacheFiles) {
            if (file.getName().startsWith("session-media-")) {
                clearDirectory(file);
            }
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        connectionGeneration++;
        monitorGeneration++;
        mainHandler.removeCallbacks(historyExpiryRunnable);
        mainHandler.removeCallbacks(monitorRetryRunnable);
        monitorRetryScheduled = false;
        closeMonitorClients();
        ChatClient oldClient = chatClient;
        chatClient = null;
        if (oldClient != null) {
            oldClient.close();
        }
        mediaExecutor.shutdownNow();
        clearIncomingMedia();
        receiptTrackers.clear();
        File oldSessionDirectory = sessionDirectory;
        sessionDirectory = null;
        events.clear();
        storageExecutor.execute(() -> clearDirectory(oldSessionDirectory));
        storageExecutor.shutdown();
        cryptoBox = null;
        listener = null;
        super.onDestroy();
    }

    private static final class ReceiptTracker {
        final Set<String> deliveredBy = new HashSet<>();
        final Set<String> seenBy = new HashSet<>();
        int expectedRecipients = -1;
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
        final String messageId;
        final String sender;
        final String mimeType;
        final String displayName;
        final long size;
        final long sentAt;
        final long createdAt;
        final ChatEvent.Reply reply;
        final boolean viewOnce;
        final File partialFile;
        final FileOutputStream output;
        final MessageDigest digest;
        long receivedBytes;
        int nextIndex;

        IncomingMedia(CryptoBox.DecryptedPacket packet, File partialFile) throws IOException {
            this.messageId = packet.messageId;
            this.sender = packet.sender;
            this.mimeType = packet.mimeType;
            this.displayName = packet.displayName;
            this.size = packet.size;
            this.sentAt = packet.sentAt;
            this.createdAt = System.currentTimeMillis();
            this.reply = packet.reply;
            this.viewOnce = packet.viewOnce;
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

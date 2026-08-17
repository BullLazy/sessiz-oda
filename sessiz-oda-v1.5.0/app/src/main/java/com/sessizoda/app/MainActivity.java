package com.sessizoda.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int MAX_VISIBLE_MESSAGES = 150;
    private static final int REQUEST_MEDIA = 301;
    private static final int REQUEST_NOTIFICATIONS = 302;
    private static final int REQUEST_SAVE_MEDIA = 303;

    private ScrollView loginScroll;
    private LinearLayout chatPanel;
    private EditText nameInput;
    private EditText serverInput;
    private EditText roomInput;
    private EditText secretInput;
    private EditText messageInput;
    private Spinner retentionSpinner;
    private Button connectButton;
    private Button sendButton;
    private Button mediaButton;
    private TextView loginStatus;
    private TextView roomTitle;
    private TextView connectionStatus;
    private LinearLayout messagesContainer;
    private ScrollView messagesScroll;
    private View savedRoomsSection;
    private LinearLayout savedRoomsContainer;
    private View replyPanel;
    private TextView replyText;

    private ChatService chatService;
    private LocalStore localStore;
    private boolean serviceBound;
    private boolean activityVisible;
    private boolean connected;
    private boolean connecting;
    private boolean mediaSupported;
    private boolean advancedSupported;
    private boolean notificationPermissionAsked;
    private boolean hasNotificationRooms;
    private String displayName = "";
    private int presence;
    private long retentionMs = RetentionPolicy.DEFAULT_MS;
    private long lastRenderedEventId;
    private Uri pendingMediaUri;
    private boolean pendingMediaViewOnce;
    private ChatEvent.Reply pendingMediaReply;
    private ChatEvent replyTarget;
    private File pendingDownloadFile;
    private String pendingDownloadMime;
    private String pendingDownloadName;
    private final Map<String, View> messageViews = new HashMap<>();
    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();

    private final ChatService.Listener serviceListener = new ChatService.Listener() {
        @Override
        public void onSessionState(
                boolean sessionConnecting,
                boolean sessionConnected,
                String sessionRoom,
                String sessionName,
                int sessionPresence,
                boolean sessionMediaSupported,
                boolean sessionAdvancedSupported,
                long sessionRetentionMs
        ) {
            runOnUiThread(() -> applySessionState(
                    sessionConnecting,
                    sessionConnected,
                    sessionRoom,
                    sessionName,
                    sessionPresence,
                    sessionMediaSupported,
                    sessionAdvancedSupported,
                    sessionRetentionMs
            ));
        }

        @Override
        public void onEvent(ChatEvent event) {
            runOnUiThread(() -> renderEvent(event));
        }

        @Override
        public void onHistoryReset(List<ChatEvent> events) {
            runOnUiThread(() -> replaceVisibleHistory(events));
        }

        @Override
        public void onError(String message) {
            runOnUiThread(() -> {
                if (connected) {
                    addSystemMessage(message);
                } else {
                    showLoginError(message);
                }
            });
        }

        @Override
        public void onDisconnected() {
            runOnUiThread(() -> {
                connected = false;
                connecting = false;
                mediaSupported = false;
                advancedSupported = false;
                sendButton.setEnabled(false);
                mediaButton.setEnabled(false);
                connectionStatus.setText(R.string.status_disconnected);
                addSystemMessage("Bağlantı kapandı. Yeniden girmek için Çık düğmesine dokunun.");
            });
        }

        @Override
        public void onTransferProgress(String status, boolean active) {
            runOnUiThread(() -> {
                mediaButton.setEnabled(connected && mediaSupported && !active);
                if (active || (status != null && !status.isEmpty())) {
                    connectionStatus.setText(status);
                } else {
                    updatePresenceText();
                }
                if (!active && "Medya gönderildi".equals(status)) {
                    Toast.makeText(MainActivity.this, status, Toast.LENGTH_SHORT).show();
                    updatePresenceText();
                }
            });
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            ChatService.LocalBinder localBinder = (ChatService.LocalBinder) binder;
            chatService = localBinder.getService();
            serviceBound = true;
            chatService.setAppVisible(activityVisible);
            chatService.setListener(serviceListener, lastRenderedEventId);
            if (pendingMediaUri != null && chatService.isConnected()) {
                Uri uri = pendingMediaUri;
                boolean viewOnce = pendingMediaViewOnce;
                ChatEvent.Reply reply = pendingMediaReply;
                pendingMediaUri = null;
                pendingMediaViewOnce = false;
                pendingMediaReply = null;
                chatService.sendMedia(uri, viewOnce, reply);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            chatService = null;
            connected = false;
            connecting = false;
            mediaSupported = false;
            advancedSupported = false;
            sendButton.setEnabled(false);
            mediaButton.setEnabled(false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        setContentView(R.layout.activity_main);

        loginScroll = findViewById(R.id.login_scroll);
        chatPanel = findViewById(R.id.chat_panel);
        nameInput = findViewById(R.id.name_input);
        serverInput = findViewById(R.id.server_input);
        roomInput = findViewById(R.id.room_input);
        secretInput = findViewById(R.id.secret_input);
        messageInput = findViewById(R.id.message_input);
        retentionSpinner = findViewById(R.id.retention_spinner);
        connectButton = findViewById(R.id.connect_button);
        sendButton = findViewById(R.id.send_button);
        mediaButton = findViewById(R.id.media_button);
        loginStatus = findViewById(R.id.login_status);
        roomTitle = findViewById(R.id.room_title);
        connectionStatus = findViewById(R.id.connection_status);
        messagesContainer = findViewById(R.id.messages_container);
        messagesScroll = findViewById(R.id.messages_scroll);
        savedRoomsSection = findViewById(R.id.saved_rooms_section);
        savedRoomsContainer = findViewById(R.id.saved_rooms_container);
        replyPanel = findViewById(R.id.reply_panel);
        replyText = findViewById(R.id.reply_text);
        Button leaveButton = findViewById(R.id.leave_button);
        Button replyCancelButton = findViewById(R.id.reply_cancel_button);

        localStore = LocalStore.get(this);
        ArrayAdapter<CharSequence> retentionAdapter = ArrayAdapter.createFromResource(
                this,
                R.array.retention_options,
                android.R.layout.simple_spinner_item
        );
        retentionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        retentionSpinner.setAdapter(retentionAdapter);
        retentionSpinner.setSelection(RetentionPolicy.indexOf(RetentionPolicy.DEFAULT_MS));

        nameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        serverInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(300)});
        roomInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        secretInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});
        messageInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2_000)});

        connectButton.setOnClickListener(view -> connect());
        sendButton.setOnClickListener(view -> sendMessage());
        mediaButton.setOnClickListener(view -> chooseMedia());
        leaveButton.setOnClickListener(view -> returnToLogin());
        replyCancelButton.setOnClickListener(view -> clearReply());
        secretInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                connect();
                return true;
            }
            return false;
        });
        messageInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });
        configureKeyboardLayout();
        refreshSavedRooms(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityVisible = true;
        refreshSavedRooms(false);
        Intent serviceIntent = new Intent(this, ChatService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        startSavedRoomMonitoring();
    }

    @Override
    protected void onStop() {
        activityVisible = false;
        if (serviceBound) {
            chatService.setAppVisible(false);
            chatService.setListener(null, 0);
            unbindService(serviceConnection);
            serviceBound = false;
            chatService = null;
        }
        super.onStop();
    }

    private void connect() {
        if (connected || connecting) {
            return;
        }

        String name = nameInput.getText().toString().trim();
        String rawServer = serverInput.getText().toString().trim();
        String room = roomInput.getText().toString().trim();
        String secret = secretInput.getText().toString();
        int retentionIndex = retentionSpinner.getSelectedItemPosition();

        if (name.length() < 2) {
            showLoginError("Görünen ad en az 2 karakter olmalı.");
            return;
        }
        String serverUrl = normalizeServerUrl(rawServer);
        if (serverUrl == null) {
            showLoginError("Sunucu adresi wss:// ile başlamalı ve geçerli olmalı.");
            return;
        }
        if (room.length() < 3) {
            showLoginError("Oda kodu en az 3 karakter olmalı.");
            return;
        }
        if (secret.length() < 12) {
            showLoginError("Ortak parola en az 12 karakter olmalı.");
            return;
        }
        if (retentionIndex < 0 || retentionIndex >= RetentionPolicy.VALUES.length) {
            showLoginError("Sohbet silinme süresi geçersiz.");
            return;
        }
        long selectedRetention = RetentionPolicy.VALUES[retentionIndex];

        askNotificationPermission();
        connecting = true;
        connectButton.setEnabled(false);
        loginStatus.setTextColor(Color.parseColor("#3157D5"));
        loginStatus.setText(R.string.status_connecting);

        Intent intent = new Intent(this, ChatService.class)
                .setAction(ChatService.ACTION_CONNECT)
                .putExtra(ChatService.EXTRA_SERVER, serverUrl)
                .putExtra(ChatService.EXTRA_ROOM, room)
                .putExtra(ChatService.EXTRA_SECRET, secret)
                .putExtra(ChatService.EXTRA_NAME, name)
                .putExtra(ChatService.EXTRA_RETENTION_MS, selectedRetention);
        startForegroundService(intent);
        secretInput.setText("");
    }

    private void applySessionState(
            boolean sessionConnecting,
            boolean sessionConnected,
            String sessionRoom,
            String sessionName,
            int sessionPresence,
            boolean sessionMediaSupported,
            boolean sessionAdvancedSupported,
            long sessionRetentionMs
    ) {
        boolean newlyConnected = sessionConnected && !connected;
        connecting = sessionConnecting;
        connected = sessionConnected;
        displayName = sessionName == null ? "" : sessionName;
        presence = sessionPresence;
        mediaSupported = sessionMediaSupported;
        advancedSupported = sessionAdvancedSupported;
        retentionMs = RetentionPolicy.normalize(sessionRetentionMs);
        connectButton.setEnabled(!connecting && !connected);

        if (connected) {
            loginScroll.setVisibility(View.GONE);
            chatPanel.setVisibility(View.VISIBLE);
            roomTitle.setText(sessionRoom);
            sendButton.setEnabled(true);
            mediaButton.setEnabled(mediaSupported);
            loginStatus.setText("");
            retentionSpinner.setSelection(RetentionPolicy.indexOf(retentionMs));
            updatePresenceText();
            messageInput.requestFocus();
            if (newlyConnected) {
                refreshSavedRooms(false);
                startSavedRoomMonitoring();
            }
        } else if (connecting) {
            loginStatus.setTextColor(Color.parseColor("#3157D5"));
            loginStatus.setText(R.string.status_connecting);
        } else {
            sendButton.setEnabled(false);
            mediaButton.setEnabled(false);
            if (chatPanel.getVisibility() == View.VISIBLE) {
                connectionStatus.setText(R.string.status_disconnected);
            }
        }
    }

    private void sendMessage() {
        String message = messageInput.getText().toString().trim();
        if (message.isEmpty()) {
            return;
        }
        if (!serviceBound || chatService == null || !connected) {
            Toast.makeText(this, "Bağlantı açık değil.", Toast.LENGTH_SHORT).show();
            return;
        }
        ChatEvent.Reply reply = ChatEvent.Reply.from(replyTarget);
        if (chatService.sendText(message, reply)) {
            messageInput.setText("");
            clearReply();
        }
    }

    private void chooseMedia() {
        if (!connected) {
            Toast.makeText(this, "Bağlantı açık değil.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!mediaSupported) {
            Toast.makeText(
                    this,
                    "Sunucu medya desteği için güncellenmemiş.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        try {
            startActivityForResult(picker, REQUEST_MEDIA);
        } catch (RuntimeException exception) {
            Toast.makeText(this, "Dosya seçici açılamadı.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SAVE_MEDIA) {
            handleDownloadResult(resultCode, data);
            return;
        }
        if (requestCode != REQUEST_MEDIA || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        if (advancedSupported) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.media_send_mode_title)
                    .setItems(
                            new String[]{
                                    getString(R.string.media_send_normal),
                                    getString(R.string.media_send_view_once)
                            },
                            (dialog, which) -> sendSelectedMedia(uri, which == 1)
                    )
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            sendSelectedMedia(uri, false);
        }
    }

    private void sendSelectedMedia(Uri uri, boolean viewOnce) {
        ChatEvent.Reply reply = ChatEvent.Reply.from(replyTarget);
        if (serviceBound && chatService != null && connected) {
            if (chatService.sendMedia(uri, viewOnce, reply)) {
                clearReply();
            }
            return;
        }
        pendingMediaUri = uri;
        pendingMediaViewOnce = viewOnce;
        pendingMediaReply = reply;
        clearReply();
    }

    private void handleDownloadResult(int resultCode, Intent data) {
        File source = pendingDownloadFile;
        String name = pendingDownloadName;
        pendingDownloadFile = null;
        pendingDownloadMime = null;
        pendingDownloadName = null;
        if (
                resultCode != RESULT_OK ||
                data == null ||
                data.getData() == null ||
                source == null ||
                !source.isFile()
        ) {
            return;
        }
        Uri destination = data.getData();
        downloadExecutor.execute(() -> {
            boolean saved = false;
            byte[] buffer = new byte[64 * 1024];
            try (FileInputStream input = new FileInputStream(source);
                 OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) {
                    throw new IOException("Hedef açılamadı.");
                }
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.flush();
                saved = true;
            } catch (IOException ignored) {
            }
            boolean completed = saved;
            runOnUiThread(() -> Toast.makeText(
                    MainActivity.this,
                    completed
                            ? getString(R.string.media_downloaded, name)
                            : getString(R.string.media_download_failed),
                    Toast.LENGTH_LONG
            ).show());
        });
    }

    private void askNotificationPermission() {
        if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !notificationPermissionAsked
        ) {
            notificationPermissionAsked = true;
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (
                requestCode == REQUEST_NOTIFICATIONS &&
                (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)
        ) {
            Toast.makeText(
                    this,
                    "Bildirim izni verilmedi; mesaj bildirimi gösterilmeyecek.",
                    Toast.LENGTH_LONG
            ).show();
        } else if (
                requestCode == REQUEST_NOTIFICATIONS &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            refreshSavedRooms(false);
            startSavedRoomMonitoring();
        }
    }

    private void returnToLogin() {
        if (serviceBound && chatService != null) {
            chatService.leave();
        }
        connected = false;
        connecting = false;
        mediaSupported = false;
        advancedSupported = false;
        displayName = "";
        presence = 0;
        lastRenderedEventId = 0;
        pendingMediaUri = null;
        pendingMediaViewOnce = false;
        pendingMediaReply = null;
        pendingDownloadFile = null;
        pendingDownloadMime = null;
        pendingDownloadName = null;
        messageViews.clear();
        clearReply();
        messagesContainer.removeAllViews();
        messageInput.setText("");
        secretInput.setText("");
        sendButton.setEnabled(false);
        mediaButton.setEnabled(false);
        connectButton.setEnabled(true);
        connectionStatus.setText(R.string.status_connecting);
        loginStatus.setText("");
        chatPanel.setVisibility(View.GONE);
        loginScroll.setVisibility(View.VISIBLE);
        refreshSavedRooms(true);
        startSavedRoomMonitoring();
    }

    private void replaceVisibleHistory(List<ChatEvent> events) {
        messagesContainer.removeAllViews();
        messageViews.clear();
        lastRenderedEventId = 0;
        for (ChatEvent event : new ArrayList<>(events)) {
            renderEvent(event);
        }
    }

    private void refreshSavedRooms(boolean prefillLatest) {
        List<LocalStore.SavedRoom> rooms;
        try {
            rooms = localStore.getSavedRooms();
        } catch (IOException | GeneralSecurityException exception) {
            hasNotificationRooms = false;
            savedRoomsSection.setVisibility(View.GONE);
            return;
        }
        hasNotificationRooms = false;
        for (LocalStore.SavedRoom room : rooms) {
            if (room.notificationsReady) {
                hasNotificationRooms = true;
                break;
            }
        }
        savedRoomsContainer.removeAllViews();
        savedRoomsSection.setVisibility(rooms.isEmpty() ? View.GONE : View.VISIBLE);
        for (LocalStore.SavedRoom room : rooms) {
            Button roomButton = new Button(this);
            roomButton.setAllCaps(false);
            roomButton.setBackgroundResource(R.drawable.secondary_button);
            roomButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            roomButton.setSingleLine(false);
            roomButton.setMaxLines(3);
            roomButton.setMinHeight(dp(58));
            roomButton.setPadding(dp(14), dp(8), dp(14), dp(8));
            roomButton.setTextColor(Color.parseColor("#2444AE"));
            roomButton.setTextSize(14);
            roomButton.setText(savedRoomLabel(room));
            roomButton.setOnClickListener(view -> selectSavedRoom(room));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.bottomMargin = dp(8);
            roomButton.setLayoutParams(params);
            savedRoomsContainer.addView(roomButton);
        }
        if (prefillLatest && !rooms.isEmpty() && !connected && !connecting) {
            selectSavedRoom(rooms.get(0));
        }
    }

    private String savedRoomLabel(LocalStore.SavedRoom room) {
        String host = Uri.parse(room.server).getHost();
        if (host == null || host.isEmpty()) {
            host = room.server;
        }
        String count = room.eventCount == 0
                ? getString(R.string.saved_room_empty)
                : getResources().getQuantityString(
                        R.plurals.saved_room_messages,
                        room.eventCount,
                        room.eventCount
                );
        return getString(
                R.string.saved_room_card,
                room.room,
                host,
                retentionLabel(room.retentionMs),
                count
        );
    }

    private void selectSavedRoom(LocalStore.SavedRoom room) {
        nameInput.setText(room.displayName);
        serverInput.setText(room.server);
        roomInput.setText(room.room);
        retentionSpinner.setSelection(RetentionPolicy.indexOf(room.retentionMs));
        loginStatus.setText("");
    }

    private void startSavedRoomMonitoring() {
        if (!hasNotificationRooms) {
            return;
        }
        if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            askNotificationPermission();
            return;
        }
        Intent intent = new Intent(this, ChatService.class)
                .setAction(ChatService.ACTION_MONITOR);
        startForegroundService(intent);
    }

    private String retentionLabel(long value) {
        if (value == RetentionPolicy.HOUR_MS) {
            return getString(R.string.retention_one_hour);
        }
        if (value == 6L * RetentionPolicy.HOUR_MS) {
            return getString(R.string.retention_six_hours);
        }
        if (value == 3L * RetentionPolicy.DAY_MS) {
            return getString(R.string.retention_three_days);
        }
        if (value == 7L * RetentionPolicy.DAY_MS) {
            return getString(R.string.retention_seven_days);
        }
        return getString(R.string.retention_one_day);
    }

    private void renderEvent(ChatEvent event) {
        if (event.id <= lastRenderedEventId) {
            return;
        }
        lastRenderedEventId = event.id;
        switch (event.type) {
            case ChatEvent.TYPE_TEXT:
                addTextMessage(event);
                break;
            case ChatEvent.TYPE_MEDIA:
                addMediaMessage(event);
                break;
            case ChatEvent.TYPE_SYSTEM:
                addSystemMessage(event.text);
                break;
            default:
                break;
        }
    }

    private void showLoginError(String message) {
        connecting = false;
        connectButton.setEnabled(true);
        loginStatus.setTextColor(Color.parseColor("#B42318"));
        loginStatus.setText(message);
    }

    private String normalizeServerUrl(String rawUrl) {
        if (rawUrl.isEmpty()) {
            return null;
        }
        try {
            Uri uri = Uri.parse(rawUrl);
            if (!"wss".equalsIgnoreCase(uri.getScheme()) || TextUtils.isEmpty(uri.getHost())) {
                return null;
            }
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                return null;
            }
            String path = uri.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                return uri.buildUpon().path("/chat").build().toString();
            }
            if (!"/chat".equals(path)) {
                return null;
            }
            return uri.toString();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void addTextMessage(ChatEvent event) {
        LinearLayout bubble = createBubble(event.own);
        int maxContentWidth = maxBubbleContentWidth();
        bubble.addView(createSenderView(event.sender, event.own, maxContentWidth));
        addReplyPreview(bubble, event, maxContentWidth);

        TextView messageView = new TextView(this);
        messageView.setText(event.text);
        messageView.setTextColor(event.own ? Color.WHITE : Color.parseColor("#18212F"));
        messageView.setTextSize(15);
        messageView.setLineSpacing(0, 1.08f);
        messageView.setTextIsSelectable(false);
        messageView.setMaxWidth(maxContentWidth);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        messageParams.topMargin = dp(3);
        messageView.setLayoutParams(messageParams);
        bubble.addView(messageView);

        String time = addTimeView(bubble, event);
        bubble.setContentDescription(getString(
                R.string.message_accessibility,
                event.sender,
                event.text,
                time
        ));
        bubble.setOnLongClickListener(view -> {
            showTextActions(event);
            return true;
        });
        registerMessageBubble(event, bubble);
    }

    private void addMediaMessage(ChatEvent event) {
        LinearLayout bubble = createBubble(event.own);
        int maxContentWidth = maxBubbleContentWidth();
        bubble.addView(createSenderView(event.sender, event.own, maxContentWidth));
        addReplyPreview(bubble, event, maxContentWidth);

        View mediaContent;
        if (event.viewOnce) {
            String label;
            if (event.own) {
                label = "① Tek gösterimlik " +
                        (event.mimeType.startsWith("image/") ? "görsel" : "video") +
                        " gönderildi";
            } else if (event.viewOnceConsumed || event.mediaFile == null || !event.mediaFile.isFile()) {
                label = "① Tek gösterimlik içerik açıldı";
            } else {
                label = "① Tek gösterimlik " +
                        (event.mimeType.startsWith("image/") ? "görsel" : "video") +
                        "\nAçmak için dokunun";
            }
            TextView viewOnceView = createMediaFallback(label, event.own, maxContentWidth);
            if (
                    !event.own &&
                    !event.viewOnceConsumed &&
                    event.mediaFile != null &&
                    event.mediaFile.isFile()
            ) {
                viewOnceView.setOnClickListener(view -> openViewOnce(event));
            }
            mediaContent = viewOnceView;
            bubble.addView(viewOnceView);
        } else if (
                event.mimeType.startsWith("image/") &&
                event.mediaFile != null &&
                event.mediaFile.isFile()
        ) {
            Bitmap preview = decodeSampledBitmap(event.mediaFile, 1_600, 1_600);
            if (preview != null) {
                ImageView imageView = new ImageView(this);
                imageView.setImageBitmap(preview);
                imageView.setAdjustViewBounds(true);
                imageView.setMaxWidth(maxContentWidth);
                imageView.setMaxHeight(dp(320));
                imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                imageView.setContentDescription(getString(R.string.media_image_description));
                LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                        maxContentWidth,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                imageParams.topMargin = dp(6);
                imageView.setLayoutParams(imageParams);
                imageView.setOnClickListener(view -> showImageDialog(event.mediaFile));
                bubble.addView(imageView);
                mediaContent = imageView;
            } else {
                mediaContent = createMediaFallback(
                        "Görsel açılamadı",
                        event.own,
                        maxContentWidth
                );
                bubble.addView(mediaContent);
            }
        } else if (event.mediaFile != null && event.mediaFile.isFile()) {
            String label = "▶ Video\n" + event.displayName + " • " + formatBytes(event.size);
            TextView videoView = createMediaFallback(label, event.own, maxContentWidth);
            videoView.setContentDescription(getString(R.string.media_video_description));
            videoView.setOnClickListener(view -> showVideoDialog(event.mediaFile));
            bubble.addView(videoView);
            mediaContent = videoView;
        } else {
            mediaContent = createMediaFallback(
                    "Medya artık kullanılamıyor",
                    event.own,
                    maxContentWidth
            );
            bubble.addView(mediaContent);
        }

        View.OnLongClickListener actions = view -> {
            showMediaActions(event);
            return true;
        };
        mediaContent.setOnLongClickListener(actions);
        bubble.setOnLongClickListener(actions);
        addTimeView(bubble, event);
        registerMessageBubble(event, bubble);
    }

    private void addReplyPreview(LinearLayout bubble, ChatEvent event, int maxWidth) {
        if (
                event.replyMessageId == null ||
                event.replySender == null ||
                event.replyPreview == null
        ) {
            return;
        }
        TextView preview = new TextView(this);
        preview.setText(getString(
                R.string.reply_quote,
                event.replySender,
                event.replyPreview
        ));
        preview.setTextColor(event.own ? Color.WHITE : Color.parseColor("#2444AE"));
        preview.setTextSize(12);
        preview.setMaxLines(3);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        preview.setMaxWidth(maxWidth);
        preview.setPadding(dp(10), dp(7), dp(10), dp(7));
        GradientDrawable background = new GradientDrawable();
        background.setColor(event.own
                ? Color.parseColor("#496BE0")
                : Color.parseColor("#EEF2FF"));
        background.setCornerRadius(dp(9));
        background.setStroke(dp(1), event.own
                ? Color.parseColor("#7590EC")
                : Color.parseColor("#C7D2FE"));
        preview.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(5);
        params.bottomMargin = dp(3);
        preview.setLayoutParams(params);
        preview.setOnClickListener(view -> scrollToMessage(event.replyMessageId));
        bubble.addView(preview);
    }

    private LinearLayout createBubble(boolean ownMessage) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));

        GradientDrawable background = new GradientDrawable();
        background.setColor(ownMessage ? Color.parseColor("#3157D5") : Color.WHITE);
        background.setCornerRadius(dp(15));
        if (!ownMessage) {
            background.setStroke(dp(1), Color.parseColor("#DDE3EC"));
        }
        bubble.setBackground(background);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                bubbleWidth(),
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = ownMessage ? Gravity.END : Gravity.START;
        params.setMargins(dp(4), dp(4), dp(4), dp(6));
        bubble.setLayoutParams(params);
        return bubble;
    }

    private TextView createSenderView(String sender, boolean ownMessage, int maxWidth) {
        TextView senderView = new TextView(this);
        senderView.setText(sender);
        senderView.setTextColor(ownMessage ? Color.WHITE : Color.parseColor("#18212F"));
        senderView.setTextSize(13);
        senderView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        senderView.setMaxWidth(maxWidth);
        return senderView;
    }

    private TextView createMediaFallback(String label, boolean ownMessage, int maxWidth) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(ownMessage ? Color.WHITE : Color.parseColor("#18212F"));
        view.setTextSize(15);
        view.setGravity(Gravity.CENTER);
        view.setMaxWidth(maxWidth);
        view.setMinWidth(dp(190));
        view.setMinHeight(dp(96));
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        return view;
    }

    private String addTimeView(LinearLayout bubble, ChatEvent event) {
        String time = DateFormat.getTimeFormat(this).format(new Date(event.sentAt));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.topMargin = dp(4);
        row.setLayoutParams(rowParams);

        TextView timeView = new TextView(this);
        timeView.setText(time);
        timeView.setTextColor(event.own
                ? Color.parseColor("#DCE5FF")
                : Color.parseColor("#64748B"));
        timeView.setTextSize(11);
        timeView.setGravity(Gravity.END);
        row.addView(timeView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        if (event.own && event.deliveryStatus != ChatEvent.STATUS_NONE) {
            TextView statusView = new TextView(this);
            String status;
            if (event.deliveryStatus == ChatEvent.STATUS_PENDING) {
                status = " ◷";
            } else if (event.deliveryStatus == ChatEvent.STATUS_SENT) {
                status = " ✓";
            } else {
                status = " ✓✓";
            }
            statusView.setText(status);
            statusView.setTextSize(12);
            statusView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            statusView.setTextColor(event.deliveryStatus == ChatEvent.STATUS_SEEN
                    ? Color.parseColor("#7EE7FF")
                    : Color.parseColor("#DCE5FF"));
            row.addView(statusView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
        }
        bubble.addView(row);
        return time;
    }

    private void showTextActions(ChatEvent event) {
        if (event.messageId == null) {
            new AlertDialog.Builder(this)
                    .setItems(
                            new String[]{getString(R.string.action_copy)},
                            (dialog, which) -> copyMessage(event.text)
                    )
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setItems(
                        new String[]{
                                getString(R.string.action_reply),
                                getString(R.string.action_copy)
                        },
                        (dialog, which) -> {
                            if (which == 0) {
                                startReply(event);
                            } else {
                                copyMessage(event.text);
                            }
                        }
                )
                .show();
    }

    private void showMediaActions(ChatEvent event) {
        boolean canReply = event.messageId != null;
        boolean canDownload =
                !event.viewOnce &&
                event.mediaFile != null &&
                event.mediaFile.isFile();
        List<String> options = new ArrayList<>();
        if (canReply) {
            options.add(getString(R.string.action_reply));
        }
        if (canDownload) {
            options.add(getString(R.string.action_download));
        }
        if (options.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    String selected = options.get(which);
                    if (selected.equals(getString(R.string.action_reply))) {
                        startReply(event);
                    } else {
                        downloadMedia(event);
                    }
                })
                .show();
    }

    private void startReply(ChatEvent event) {
        ChatEvent.Reply reply = ChatEvent.Reply.from(event);
        if (reply == null) {
            Toast.makeText(this, R.string.reply_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        replyTarget = event;
        replyText.setText(getString(R.string.reply_quote, event.sender, reply.preview));
        replyPanel.setVisibility(View.VISIBLE);
        messageInput.requestFocus();
    }

    private void clearReply() {
        replyTarget = null;
        if (replyPanel != null) {
            replyPanel.setVisibility(View.GONE);
        }
        if (replyText != null) {
            replyText.setText("");
        }
    }

    private void scrollToMessage(String messageId) {
        View target = messageViews.get(messageId);
        if (target == null || target.getParent() == null) {
            Toast.makeText(this, R.string.reply_original_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        messagesScroll.smoothScrollTo(0, Math.max(0, target.getTop() - dp(12)));
    }

    private void registerMessageBubble(ChatEvent event, View bubble) {
        addToMessageList(bubble);
        if (event.messageId != null) {
            messageViews.put(event.messageId, bubble);
        }
        messageViews.entrySet().removeIf(entry -> entry.getValue().getParent() == null);
    }

    private void downloadMedia(ChatEvent event) {
        if (
                event.viewOnce ||
                event.mediaFile == null ||
                !event.mediaFile.isFile()
        ) {
            return;
        }
        pendingDownloadFile = event.mediaFile;
        pendingDownloadMime = event.mimeType;
        pendingDownloadName = event.displayName;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(event.mimeType)
                .putExtra(Intent.EXTRA_TITLE, event.displayName);
        try {
            startActivityForResult(intent, REQUEST_SAVE_MEDIA);
        } catch (RuntimeException exception) {
            pendingDownloadFile = null;
            pendingDownloadMime = null;
            pendingDownloadName = null;
            Toast.makeText(this, R.string.media_download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void openViewOnce(ChatEvent event) {
        if (
                !serviceBound ||
                chatService == null ||
                event.messageId == null ||
                event.mediaFile == null ||
                !event.mediaFile.isFile() ||
                !chatService.claimViewOnce(event.messageId)
        ) {
            Toast.makeText(this, R.string.view_once_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        Runnable finished = () -> {
            if (serviceBound && chatService != null) {
                chatService.finishViewOnce(event.messageId);
            } else {
                event.mediaFile.delete();
            }
        };
        if (event.mimeType.startsWith("image/")) {
            showImageDialog(event.mediaFile, finished);
        } else {
            showVideoDialog(event.mediaFile, finished);
        }
    }

    private void showImageDialog(File file) {
        showImageDialog(file, null);
    }

    private void showImageDialog(File file, Runnable onDismiss) {
        Bitmap bitmap = decodeSampledBitmap(file, 2_048, 2_048);
        if (bitmap == null) {
            Toast.makeText(this, "Görsel açılamadı.", Toast.LENGTH_SHORT).show();
            if (onDismiss != null) {
                onDismiss.run();
            }
            return;
        }
        Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        ImageView image = new ImageView(this);
        image.setBackgroundColor(Color.BLACK);
        image.setImageBitmap(bitmap);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setOnClickListener(view -> dialog.dismiss());
        dialog.setContentView(image);
        if (onDismiss != null) {
            dialog.setOnDismissListener(ignored -> onDismiss.run());
        }
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private void showVideoDialog(File file) {
        showVideoDialog(file, null);
    }

    private void showVideoDialog(File file, Runnable onDismiss) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        VideoView video = new VideoView(this);
        frame.addView(video, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        ));
        dialog.setContentView(frame);
        dialog.setOnDismissListener(ignored -> {
            video.stopPlayback();
            if (onDismiss != null) {
                onDismiss.run();
            }
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        MediaController controller = new MediaController(this);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        video.setVideoPath(file.getAbsolutePath());
        video.setOnPreparedListener(player -> video.start());
        video.setOnErrorListener((player, what, extra) -> {
            Toast.makeText(this, "Video bu cihazda açılamadı.", Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private static Bitmap decodeSampledBitmap(File file, int requestedWidth, int requestedHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (
                bounds.outWidth / (sample * 2) >= requestedWidth &&
                bounds.outHeight / (sample * 2) >= requestedHeight
        ) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024f * 1024f));
        }
        return String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024f);
    }

    private void copyMessage(String message) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) {
            Toast.makeText(this, R.string.message_copy_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData clip = ClipData.newPlainText(getString(R.string.clipboard_label), message);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            Toast.makeText(this, R.string.message_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void configureKeyboardLayout() {
        View root = findViewById(R.id.root_container);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets bars = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                );
                Insets keyboard = windowInsets.getInsets(WindowInsets.Type.ime());
                view.setPadding(
                        bars.left,
                        bars.top,
                        bars.right,
                        Math.max(bars.bottom, keyboard.bottom)
                );
                return windowInsets;
            });
            root.requestApplyInsets();
        }
        messageInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                scrollToLatestMessage();
            }
        });
        chatPanel.addOnLayoutChangeListener((view, left, top, right, bottom,
                                             oldLeft, oldTop, oldRight, oldBottom) -> {
            int height = bottom - top;
            int oldHeight = oldBottom - oldTop;
            if (connected && height != oldHeight) {
                scrollToLatestMessage();
            }
        });
    }

    private void addSystemMessage(String message) {
        TextView notice = new TextView(this);
        notice.setText(message);
        notice.setTextColor(Color.parseColor("#64748B"));
        notice.setTextSize(12);
        notice.setGravity(Gravity.CENTER);
        notice.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(4), 0, dp(4));
        notice.setLayoutParams(params);
        addToMessageList(notice);
    }

    private void addToMessageList(View view) {
        if (messagesContainer.getChildCount() >= MAX_VISIBLE_MESSAGES) {
            messagesContainer.removeViewAt(0);
        }
        messagesContainer.addView(view);
        scrollToLatestMessage();
    }

    private void updatePresenceText() {
        if (connected) {
            connectionStatus.setText(getResources().getQuantityString(
                    R.plurals.status_people_with_retention,
                    presence,
                    presence,
                    retentionLabel(retentionMs)
            ));
        }
    }

    private void scrollToLatestMessage() {
        messagesScroll.post(() -> messagesScroll.fullScroll(View.FOCUS_DOWN));
    }

    private int maxBubbleContentWidth() {
        return Math.max(dp(132), bubbleWidth() - dp(28));
    }

    private int bubbleWidth() {
        int available = getResources().getDisplayMetrics().widthPixels - dp(32);
        return Math.min(dp(300), Math.max(dp(160), available));
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        View root = findViewById(R.id.root_container);
        root.requestApplyInsets();
        scrollToLatestMessage();
    }

    @Override
    protected void onDestroy() {
        downloadExecutor.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

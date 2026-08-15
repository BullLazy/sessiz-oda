package com.sessizoda.app;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.util.Date;

public final class MainActivity extends Activity {
    private static final int MAX_VISIBLE_MESSAGES = 150;
    private static final int REQUEST_MEDIA = 301;
    private static final int REQUEST_NOTIFICATIONS = 302;

    private ScrollView loginScroll;
    private LinearLayout chatPanel;
    private EditText nameInput;
    private EditText serverInput;
    private EditText roomInput;
    private EditText secretInput;
    private EditText messageInput;
    private Button connectButton;
    private Button sendButton;
    private Button mediaButton;
    private TextView loginStatus;
    private TextView roomTitle;
    private TextView connectionStatus;
    private LinearLayout messagesContainer;
    private ScrollView messagesScroll;

    private ChatService chatService;
    private boolean serviceBound;
    private boolean activityVisible;
    private boolean connected;
    private boolean connecting;
    private boolean notificationPermissionAsked;
    private String displayName = "";
    private int presence;
    private long lastRenderedEventId;
    private Uri pendingMediaUri;

    private final ChatService.Listener serviceListener = new ChatService.Listener() {
        @Override
        public void onSessionState(
                boolean sessionConnecting,
                boolean sessionConnected,
                String sessionRoom,
                String sessionName,
                int sessionPresence
        ) {
            runOnUiThread(() -> applySessionState(
                    sessionConnecting,
                    sessionConnected,
                    sessionRoom,
                    sessionName,
                    sessionPresence
            ));
        }

        @Override
        public void onEvent(ChatEvent event) {
            runOnUiThread(() -> renderEvent(event));
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
                sendButton.setEnabled(false);
                mediaButton.setEnabled(false);
                connectionStatus.setText(R.string.status_disconnected);
                addSystemMessage("Bağlantı kapandı. Yeniden girmek için Çık düğmesine dokunun.");
            });
        }

        @Override
        public void onTransferProgress(String status, boolean active) {
            runOnUiThread(() -> {
                mediaButton.setEnabled(connected && !active);
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
                pendingMediaUri = null;
                chatService.sendMedia(uri);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            chatService = null;
            connected = false;
            connecting = false;
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
        connectButton = findViewById(R.id.connect_button);
        sendButton = findViewById(R.id.send_button);
        mediaButton = findViewById(R.id.media_button);
        loginStatus = findViewById(R.id.login_status);
        roomTitle = findViewById(R.id.room_title);
        connectionStatus = findViewById(R.id.connection_status);
        messagesContainer = findViewById(R.id.messages_container);
        messagesScroll = findViewById(R.id.messages_scroll);
        Button leaveButton = findViewById(R.id.leave_button);

        nameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        serverInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(300)});
        roomInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        secretInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});
        messageInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2_000)});

        connectButton.setOnClickListener(view -> connect());
        sendButton.setOnClickListener(view -> sendMessage());
        mediaButton.setOnClickListener(view -> chooseMedia());
        leaveButton.setOnClickListener(view -> returnToLogin());
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
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityVisible = true;
        Intent serviceIntent = new Intent(this, ChatService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
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
                .putExtra(ChatService.EXTRA_NAME, name);
        startForegroundService(intent);
        secretInput.setText("");
    }

    private void applySessionState(
            boolean sessionConnecting,
            boolean sessionConnected,
            String sessionRoom,
            String sessionName,
            int sessionPresence
    ) {
        connecting = sessionConnecting;
        connected = sessionConnected;
        displayName = sessionName == null ? "" : sessionName;
        presence = sessionPresence;
        connectButton.setEnabled(!connecting && !connected);

        if (connected) {
            loginScroll.setVisibility(View.GONE);
            chatPanel.setVisibility(View.VISIBLE);
            roomTitle.setText(sessionRoom);
            sendButton.setEnabled(true);
            mediaButton.setEnabled(true);
            loginStatus.setText("");
            updatePresenceText();
            messageInput.requestFocus();
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
        if (chatService.sendText(message)) {
            messageInput.setText("");
        }
    }

    private void chooseMedia() {
        if (!connected) {
            Toast.makeText(this, "Bağlantı açık değil.", Toast.LENGTH_SHORT).show();
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
        if (requestCode != REQUEST_MEDIA || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        if (serviceBound && chatService != null && connected) {
            chatService.sendMedia(uri);
        } else {
            pendingMediaUri = uri;
        }
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
        }
    }

    private void returnToLogin() {
        if (serviceBound && chatService != null) {
            chatService.leave();
        } else {
            stopService(new Intent(this, ChatService.class));
        }
        connected = false;
        connecting = false;
        displayName = "";
        presence = 0;
        lastRenderedEventId = 0;
        pendingMediaUri = null;
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

        String time = addTimeView(bubble, event.sentAt, event.own);
        bubble.setContentDescription(getString(
                R.string.message_accessibility,
                event.sender,
                event.text,
                time
        ));
        bubble.setOnLongClickListener(view -> {
            copyMessage(event.text);
            return true;
        });
        addToMessageList(bubble);
    }

    private void addMediaMessage(ChatEvent event) {
        LinearLayout bubble = createBubble(event.own);
        int maxContentWidth = maxBubbleContentWidth();
        bubble.addView(createSenderView(event.sender, event.own, maxContentWidth));

        if (event.mimeType.startsWith("image/")) {
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
            } else {
                bubble.addView(createMediaFallback("Görsel açılamadı", event.own, maxContentWidth));
            }
        } else {
            String label = "▶ Video\n" + event.displayName + " • " + formatBytes(event.size);
            TextView videoView = createMediaFallback(label, event.own, maxContentWidth);
            videoView.setContentDescription(getString(R.string.media_video_description));
            videoView.setOnClickListener(view -> showVideoDialog(event.mediaFile));
            bubble.addView(videoView);
        }

        addTimeView(bubble, event.sentAt, event.own);
        addToMessageList(bubble);
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
                LinearLayout.LayoutParams.WRAP_CONTENT,
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

    private String addTimeView(LinearLayout bubble, long sentAt, boolean ownMessage) {
        String time = DateFormat.getTimeFormat(this).format(new Date(sentAt));
        TextView timeView = new TextView(this);
        timeView.setText(time);
        timeView.setTextColor(ownMessage ? Color.parseColor("#DCE5FF") : Color.parseColor("#64748B"));
        timeView.setTextSize(11);
        timeView.setGravity(Gravity.END);
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        timeParams.topMargin = dp(4);
        timeView.setLayoutParams(timeParams);
        bubble.addView(timeView);
        return time;
    }

    private void showImageDialog(File file) {
        Bitmap bitmap = decodeSampledBitmap(file, 2_048, 2_048);
        if (bitmap == null) {
            Toast.makeText(this, "Görsel açılamadı.", Toast.LENGTH_SHORT).show();
            return;
        }
        Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        ImageView image = new ImageView(this);
        image.setBackgroundColor(Color.BLACK);
        image.setImageBitmap(bitmap);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setOnClickListener(view -> dialog.dismiss());
        dialog.setContentView(image);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private void showVideoDialog(File file) {
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
        dialog.setOnDismissListener(ignored -> video.stopPlayback());
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
                    R.plurals.status_people,
                    presence,
                    presence
            ));
        }
    }

    private void scrollToLatestMessage() {
        messagesScroll.post(() -> messagesScroll.fullScroll(View.FOCUS_DOWN));
    }

    private int maxBubbleContentWidth() {
        return Math.max(
                dp(120),
                (int) (getResources().getDisplayMetrics().widthPixels * 0.82f) - dp(28)
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

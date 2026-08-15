package com.sessizoda.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.security.GeneralSecurityException;
import java.util.Date;

public final class MainActivity extends Activity {
    private static final int MAX_VISIBLE_MESSAGES = 150;

    private ScrollView loginScroll;
    private LinearLayout chatPanel;
    private EditText nameInput;
    private EditText serverInput;
    private EditText roomInput;
    private EditText secretInput;
    private EditText messageInput;
    private Button connectButton;
    private Button sendButton;
    private TextView loginStatus;
    private TextView roomTitle;
    private TextView connectionStatus;
    private LinearLayout messagesContainer;
    private ScrollView messagesScroll;

    private ChatClient chatClient;
    private CryptoBox cryptoBox;
    private String displayName = "";
    private boolean connected;
    private int connectionGeneration;

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

    private void connect() {
        if (chatClient != null) {
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

        try {
            CryptoBox newCryptoBox = new CryptoBox(room, secret);
            String roomId = CryptoBox.roomId(room);
            String proof = newCryptoBox.authProof(roomId);
            cryptoBox = newCryptoBox;
            displayName = name;
            connected = false;
            connectButton.setEnabled(false);
            loginStatus.setTextColor(Color.parseColor("#3157D5"));
            loginStatus.setText(R.string.status_connecting);

            int generation = ++connectionGeneration;
            chatClient = new ChatClient(serverUrl, roomId, proof, new ChatClient.Listener() {
                @Override
                public void onJoined() {
                    runOnUiThread(() -> {
                        if (generation != connectionGeneration) {
                            return;
                        }
                        connected = true;
                        loginScroll.setVisibility(View.GONE);
                        chatPanel.setVisibility(View.VISIBLE);
                        roomTitle.setText(room);
                        connectionStatus.setText(R.string.status_connected);
                        sendButton.setEnabled(true);
                        secretInput.setText("");
                        loginStatus.setText("");
                        messageInput.requestFocus();
                    });
                }

                @Override
                public void onPresence(int count) {
                    runOnUiThread(() -> {
                        if (generation == connectionGeneration && connected) {
                            connectionStatus.setText(getResources().getQuantityString(R.plurals.status_people, count, count));
                        }
                    });
                }

                @Override
                public void onCipher(String payload) {
                    runOnUiThread(() -> {
                        if (generation != connectionGeneration || !connected || cryptoBox == null) {
                            return;
                        }
                        try {
                            CryptoBox.DecryptedMessage clearMessage = cryptoBox.decrypt(payload);
                            addMessage(
                                    clearMessage.sender,
                                    clearMessage.message,
                                    clearMessage.sentAt,
                                    clearMessage.sender.equals(displayName)
                            );
                        } catch (GeneralSecurityException exception) {
                            addSystemMessage("Açılamayan bir şifreli mesaj atlandı.");
                        }
                    });
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        if (generation != connectionGeneration) {
                            return;
                        }
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
                        if (generation != connectionGeneration) {
                            return;
                        }
                        if (connected) {
                            connected = false;
                            sendButton.setEnabled(false);
                            connectionStatus.setText(R.string.status_disconnected);
                            addSystemMessage("Bağlantı kapandı. Yeniden girmek için Çık düğmesine dokunun.");
                        } else {
                            chatClient = null;
                            cryptoBox = null;
                            connectButton.setEnabled(true);
                        }
                    });
                }
            });
            chatClient.connect();
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            cryptoBox = null;
            chatClient = null;
            connectButton.setEnabled(true);
            showLoginError("Güvenli bağlantı hazırlanamadı.");
        }
    }

    private void sendMessage() {
        String message = messageInput.getText().toString().trim();
        if (message.isEmpty()) {
            return;
        }
        if (!connected || chatClient == null || cryptoBox == null) {
            Toast.makeText(this, "Bağlantı açık değil.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String payload = cryptoBox.encrypt(displayName, message);
            if (chatClient.sendCipher(payload)) {
                messageInput.setText("");
            } else {
                Toast.makeText(this, "Mesaj gönderilemedi.", Toast.LENGTH_SHORT).show();
            }
        } catch (GeneralSecurityException exception) {
            Toast.makeText(this, "Mesaj şifrelenemedi.", Toast.LENGTH_SHORT).show();
        }
    }

    private void returnToLogin() {
        connectionGeneration++;
        connected = false;
        ChatClient oldClient = chatClient;
        chatClient = null;
        if (oldClient != null) {
            oldClient.close();
        }
        cryptoBox = null;
        displayName = "";
        messagesContainer.removeAllViews();
        messageInput.setText("");
        secretInput.setText("");
        sendButton.setEnabled(true);
        connectButton.setEnabled(true);
        connectionStatus.setText(R.string.status_connecting);
        loginStatus.setText("");
        chatPanel.setVisibility(View.GONE);
        loginScroll.setVisibility(View.VISIBLE);
    }

    private void showLoginError(String message) {
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

    private void addMessage(String sender, String message, long sentAt, boolean ownMessage) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        int maxContentWidth = Math.max(
                dp(120),
                (int) (getResources().getDisplayMetrics().widthPixels * 0.82f) - dp(28)
        );

        TextView senderView = new TextView(this);
        senderView.setText(sender);
        senderView.setTextColor(ownMessage ? Color.WHITE : Color.parseColor("#18212F"));
        senderView.setTextSize(13);
        senderView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        senderView.setMaxWidth(maxContentWidth);
        bubble.addView(senderView);

        TextView messageView = new TextView(this);
        messageView.setText(message);
        messageView.setTextColor(ownMessage ? Color.WHITE : Color.parseColor("#18212F"));
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
        bubble.setContentDescription(getString(R.string.message_accessibility, sender, message, time));
        bubble.setOnLongClickListener(view -> {
            copyMessage(message);
            return true;
        });
        addToMessageList(bubble);
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

    private void scrollToLatestMessage() {
        messagesScroll.post(() -> messagesScroll.fullScroll(View.FOCUS_DOWN));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        connectionGeneration++;
        ChatClient oldClient = chatClient;
        chatClient = null;
        if (oldClient != null) {
            oldClient.close();
        }
        cryptoBox = null;
        if (messagesContainer != null) {
            messagesContainer.removeAllViews();
        }
        super.onDestroy();
    }
}

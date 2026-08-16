package com.sessizoda.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

final class ChatClient {
    interface Listener {
        void onJoined(boolean mediaSupported, boolean notificationSupported);

        void onPresence(int count);

        void onCipher(String kind, String payload);

        void onError(String message);

        void onDisconnected();
    }

    private final String roomId;
    private final String authProof;
    private final String clientId;
    private final Listener listener;
    private final OkHttpClient httpClient;
    private final Request request;
    private final AtomicBoolean terminated = new AtomicBoolean(false);

    private volatile WebSocket webSocket;
    private volatile boolean manualClose;
    private volatile boolean mediaSupported;

    ChatClient(
            String serverUrl,
            String roomId,
            String authProof,
            String clientId,
            Listener listener
    ) {
        this.roomId = roomId;
        this.authProof = authProof;
        this.clientId = clientId;
        this.listener = listener;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        this.request = new Request.Builder().url(serverUrl).build();
    }

    void connect() {
        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                JSONObject join = new JSONObject();
                try {
                    join.put("type", "join");
                    join.put("room", roomId);
                    join.put("proof", authProof);
                    join.put("client", clientId);
                    join.put("media", 1);
                    if (!socket.send(join.toString())) {
                        failProtocol("Sunucuya katılım isteği gönderilemedi.");
                    }
                } catch (JSONException exception) {
                    failProtocol("Katılım isteği hazırlanamadı.");
                }
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                handleMessage(text);
            }

            @Override
            public void onClosing(WebSocket socket, int code, String reason) {
                socket.close(code, null);
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                finish();
            }

            @Override
            public void onFailure(WebSocket socket, Throwable throwable, Response response) {
                if (!manualClose) {
                    listener.onError("Bağlantı kurulamadı veya kesildi. Sunucu adresini ve interneti kontrol edin.");
                }
                finish();
            }
        });
    }

    boolean sendCipher(String kind, String payload) {
        return sendCipher(kind, null, payload);
    }

    boolean sendMediaCipher(String stage, String payload) {
        return sendCipher("media", stage, payload);
    }

    private boolean sendCipher(String kind, String stage, String payload) {
        WebSocket socket = webSocket;
        if (
                socket == null ||
                terminated.get() ||
                ("media".equals(kind) && !mediaSupported)
        ) {
            return false;
        }
        try {
            JSONObject message = new JSONObject();
            message.put("type", "cipher");
            message.put("kind", kind);
            if (stage != null) {
                message.put("stage", stage);
            }
            message.put("payload", payload);
            return socket.send(message.toString());
        } catch (JSONException exception) {
            return false;
        }
    }

    long queueSize() {
        WebSocket socket = webSocket;
        return socket == null ? 0 : socket.queueSize();
    }

    void close() {
        manualClose = true;
        WebSocket socket = webSocket;
        if (socket != null) {
            if (!socket.close(1000, "Oturum kapatıldı")) {
                socket.cancel();
            }
        }
        finish();
    }

    private void handleMessage(String text) {
        try {
            JSONObject message = new JSONObject(text);
            String type = message.optString("type", "");
            switch (type) {
                case "joined":
                    int protocol = message.optInt("protocol", 1);
                    mediaSupported =
                            protocol >= 2 &&
                            message.optInt("media", 0) == 1;
                    boolean notificationSupported =
                            protocol >= 3 &&
                            message.optInt("notifications", 0) == 1;
                    listener.onJoined(mediaSupported, notificationSupported);
                    break;
                case "presence":
                    int count = message.optInt("count", 0);
                    if (count >= 0 && count <= 100) {
                        listener.onPresence(count);
                    }
                    break;
                case "cipher":
                    String kind = message.optString("kind", "text");
                    String payload = message.optString("payload", "");
                    int payloadLimit = "media".equals(kind) ? 32_000 : 12_000;
                    if (
                            ("text".equals(kind) || "media".equals(kind)) &&
                            !payload.isEmpty() &&
                            payload.length() <= payloadLimit
                    ) {
                        listener.onCipher(kind, payload);
                    }
                    break;
                case "error":
                    String code = message.optString("code", "");
                    listener.onError(mapServerError(code));
                    if (isFatalServerError(code)) {
                        WebSocket socket = webSocket;
                        if (socket != null) {
                            socket.close(1008, "Sunucu isteği reddetti");
                        }
                    }
                    break;
                default:
                    failProtocol("Sunucudan geçersiz yanıt alındı.");
                    break;
            }
        } catch (JSONException exception) {
            failProtocol("Sunucudan geçersiz yanıt alındı.");
        }
    }

    private String mapServerError(String code) {
        switch (code) {
            case "room_full":
                return "Bu oda dolu.";
            case "rate_limited":
                return "Sunucu yoğunluğu nedeniyle bir paket atlandı; bağlantı açık kaldı.";
            case "session_replaced":
                return "Bu cihazdaki eski bağlantının yerini yeni oturum aldı.";
            case "join_required":
                return "Sunucu katılım isteğini kabul etmedi.";
            default:
                return "Sunucu isteği reddetti.";
        }
    }

    private boolean isFatalServerError(String code) {
        return "room_full".equals(code) ||
                "join_required".equals(code) ||
                "session_replaced".equals(code);
    }

    private void failProtocol(String message) {
        listener.onError(message);
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.close(1002, "Protokol hatası");
        }
    }

    private void finish() {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
        if (!manualClose) {
            listener.onDisconnected();
        }
    }
}

package com.sessizoda.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

final class RoomMonitorClient {
    interface Listener {
        void onReady();

        void onActivity(String roomId);

        void onDisconnected();
    }

    private final String clientId;
    private final List<LocalStore.NotificationRoom> rooms;
    private final Set<String> roomIds = new HashSet<>();
    private final Listener listener;
    private final OkHttpClient httpClient;
    private final Request request;
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);

    private volatile WebSocket webSocket;
    private volatile boolean manualClose;

    RoomMonitorClient(
            String serverUrl,
            String clientId,
            List<LocalStore.NotificationRoom> rooms,
            Listener listener
    ) {
        this.clientId = clientId;
        this.rooms = rooms;
        this.listener = listener;
        for (LocalStore.NotificationRoom room : rooms) {
            roomIds.add(room.roomId);
        }
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        request = new Request.Builder().url(serverUrl).build();
    }

    void connect() {
        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                try {
                    JSONObject join = new JSONObject();
                    join.put("type", "monitor");
                    join.put("client", clientId);
                    JSONArray subscriptions = new JSONArray();
                    for (LocalStore.NotificationRoom room : rooms) {
                        JSONObject subscription = new JSONObject();
                        subscription.put("room", room.roomId);
                        subscription.put("proof", room.authProof);
                        subscriptions.put(subscription);
                    }
                    join.put("rooms", subscriptions);
                    if (!socket.send(join.toString())) {
                        failProtocol();
                    }
                } catch (JSONException exception) {
                    failProtocol();
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
                finish();
            }
        });
    }

    void close() {
        manualClose = true;
        WebSocket socket = webSocket;
        if (socket != null) {
            if (!socket.close(1000, "Bildirim bağlantısı kapatıldı")) {
                socket.cancel();
            }
        }
        finish();
    }

    private void handleMessage(String text) {
        try {
            JSONObject message = new JSONObject(text);
            String type = message.optString("type", "");
            if ("joined".equals(type)) {
                if (
                        message.optInt("protocol", 0) < 3 ||
                        message.optInt("notifications", 0) != 1 ||
                        !"monitor".equals(message.optString("mode", ""))
                ) {
                    failProtocol();
                    return;
                }
                if (ready.compareAndSet(false, true)) {
                    listener.onReady();
                }
                return;
            }
            if ("activity".equals(type) && ready.get()) {
                String roomId = message.optString("room", "");
                if (roomIds.contains(roomId)) {
                    listener.onActivity(roomId);
                }
                return;
            }
            if ("error".equals(type)) {
                failProtocol();
            }
        } catch (JSONException exception) {
            failProtocol();
        }
    }

    private void failProtocol() {
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.close(1002, "Bildirim protokolü desteklenmiyor");
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

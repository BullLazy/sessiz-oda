package com.sessizoda.app;

import java.io.File;

final class ChatEvent {
    static final int TYPE_TEXT = 1;
    static final int TYPE_MEDIA = 2;
    static final int TYPE_SYSTEM = 3;

    final long id;
    final int type;
    final String sender;
    final String text;
    final long sentAt;
    final boolean own;
    final File mediaFile;
    final String mimeType;
    final String displayName;
    final long size;

    private ChatEvent(
            long id,
            int type,
            String sender,
            String text,
            long sentAt,
            boolean own,
            File mediaFile,
            String mimeType,
            String displayName,
            long size
    ) {
        this.id = id;
        this.type = type;
        this.sender = sender;
        this.text = text;
        this.sentAt = sentAt;
        this.own = own;
        this.mediaFile = mediaFile;
        this.mimeType = mimeType;
        this.displayName = displayName;
        this.size = size;
    }

    static ChatEvent text(long id, String sender, String text, long sentAt, boolean own) {
        return new ChatEvent(id, TYPE_TEXT, sender, text, sentAt, own, null, null, null, 0);
    }

    static ChatEvent media(
            long id,
            String sender,
            long sentAt,
            boolean own,
            File file,
            String mimeType,
            String displayName,
            long size
    ) {
        return new ChatEvent(id, TYPE_MEDIA, sender, null, sentAt, own, file, mimeType, displayName, size);
    }

    static ChatEvent system(long id, String text) {
        return new ChatEvent(id, TYPE_SYSTEM, null, text, System.currentTimeMillis(), false, null, null, null, 0);
    }
}

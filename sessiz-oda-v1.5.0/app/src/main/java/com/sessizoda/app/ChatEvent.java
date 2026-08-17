package com.sessizoda.app;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

final class ChatEvent {
    static final int TYPE_TEXT = 1;
    static final int TYPE_MEDIA = 2;
    static final int TYPE_SYSTEM = 3;

    static final int STATUS_NONE = 0;
    static final int STATUS_PENDING = 1;
    static final int STATUS_SENT = 2;
    static final int STATUS_DELIVERED = 3;
    static final int STATUS_SEEN = 4;

    final long id;
    final String messageId;
    final int type;
    final String sender;
    final String text;
    final long sentAt;
    final boolean own;
    final File mediaFile;
    final String mimeType;
    final String displayName;
    final long size;
    final String replyMessageId;
    final String replySender;
    final String replyPreview;
    final boolean viewOnce;
    final Set<String> deliveredBy = new HashSet<>();
    final Set<String> seenBy = new HashSet<>();

    int deliveryStatus;
    int expectedRecipients = -1;
    boolean viewOnceConsumed;
    boolean readReceiptSent;

    private ChatEvent(
            long id,
            String messageId,
            int type,
            String sender,
            String text,
            long sentAt,
            boolean own,
            File mediaFile,
            String mimeType,
            String displayName,
            long size,
            String replyMessageId,
            String replySender,
            String replyPreview,
            boolean viewOnce,
            boolean viewOnceConsumed,
            int deliveryStatus
    ) {
        this.id = id;
        this.messageId = messageId;
        this.type = type;
        this.sender = sender;
        this.text = text;
        this.sentAt = sentAt;
        this.own = own;
        this.mediaFile = mediaFile;
        this.mimeType = mimeType;
        this.displayName = displayName;
        this.size = size;
        this.replyMessageId = replyMessageId;
        this.replySender = replySender;
        this.replyPreview = replyPreview;
        this.viewOnce = viewOnce;
        this.viewOnceConsumed = viewOnceConsumed;
        this.deliveryStatus = deliveryStatus;
    }

    static ChatEvent text(
            long id,
            String messageId,
            String sender,
            String text,
            long sentAt,
            boolean own,
            Reply reply,
            int deliveryStatus
    ) {
        return new ChatEvent(
                id,
                messageId,
                TYPE_TEXT,
                sender,
                text,
                sentAt,
                own,
                null,
                null,
                null,
                0,
                reply == null ? null : reply.messageId,
                reply == null ? null : reply.sender,
                reply == null ? null : reply.preview,
                false,
                false,
                deliveryStatus
        );
    }

    static ChatEvent text(long id, String sender, String text, long sentAt, boolean own) {
        return text(id, null, sender, text, sentAt, own, null, STATUS_NONE);
    }

    static ChatEvent media(
            long id,
            String messageId,
            String sender,
            long sentAt,
            boolean own,
            File file,
            String mimeType,
            String displayName,
            long size,
            Reply reply,
            boolean viewOnce,
            boolean viewOnceConsumed,
            int deliveryStatus
    ) {
        return new ChatEvent(
                id,
                messageId,
                TYPE_MEDIA,
                sender,
                null,
                sentAt,
                own,
                file,
                mimeType,
                displayName,
                size,
                reply == null ? null : reply.messageId,
                reply == null ? null : reply.sender,
                reply == null ? null : reply.preview,
                viewOnce,
                viewOnceConsumed,
                deliveryStatus
        );
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
        return media(
                id,
                null,
                sender,
                sentAt,
                own,
                file,
                mimeType,
                displayName,
                size,
                null,
                false,
                false,
                STATUS_NONE
        );
    }

    static ChatEvent system(long id, String text) {
        return new ChatEvent(
                id,
                null,
                TYPE_SYSTEM,
                null,
                text,
                System.currentTimeMillis(),
                false,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                false,
                false,
                STATUS_NONE
        );
    }

    Reply reply() {
        if (replyMessageId == null || replySender == null || replyPreview == null) {
            return null;
        }
        return new Reply(replyMessageId, replySender, replyPreview);
    }

    static final class Reply {
        final String messageId;
        final String sender;
        final String preview;

        Reply(String messageId, String sender, String preview) {
            this.messageId = messageId;
            this.sender = sender;
            this.preview = preview;
        }

        static Reply from(ChatEvent event) {
            if (event == null || event.messageId == null || event.sender == null) {
                return null;
            }
            String preview;
            if (event.type == TYPE_TEXT) {
                preview = event.text.replaceAll("\\s+", " ").trim();
            } else if (event.viewOnce) {
                preview = "Tek gösterimlik medya";
            } else if (event.mimeType != null && event.mimeType.startsWith("image/")) {
                preview = "Görsel";
            } else {
                preview = "Video";
            }
            if (preview.length() > 160) {
                preview = preview.substring(0, 157) + "…";
            }
            return new Reply(event.messageId, event.sender, preview);
        }
    }
}

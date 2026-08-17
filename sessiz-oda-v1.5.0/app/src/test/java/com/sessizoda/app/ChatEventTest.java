package com.sessizoda.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public final class ChatEventTest {
    @Test
    public void replySnapshotKeepsMessageIdentityAndCompactText() {
        ChatEvent event = ChatEvent.text(
                1,
                "a".repeat(32),
                "Deniz",
                "Merhaba\n   dünya",
                1_000,
                false,
                null,
                ChatEvent.STATUS_NONE
        );
        ChatEvent.Reply reply = ChatEvent.Reply.from(event);
        assertNotNull(reply);
        assertEquals("a".repeat(32), reply.messageId);
        assertEquals("Deniz", reply.sender);
        assertEquals("Merhaba dünya", reply.preview);
    }

    @Test
    public void viewOnceReplyNeverExposesMediaDetails() {
        ChatEvent event = ChatEvent.media(
                2,
                "b".repeat(32),
                "Ece",
                2_000,
                false,
                null,
                "image/jpeg",
                "gizli.jpg",
                42,
                null,
                true,
                true,
                ChatEvent.STATUS_NONE
        );
        assertEquals("Tek gösterimlik medya", ChatEvent.Reply.from(event).preview);
    }
}

package com.sessizoda.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RetentionPolicyTest {
    @Test
    public void supportedDurationsRemainStable() {
        assertEquals(5, RetentionPolicy.VALUES.length);
        assertEquals(RetentionPolicy.HOUR_MS, RetentionPolicy.VALUES[0]);
        assertEquals(7L * RetentionPolicy.DAY_MS, RetentionPolicy.VALUES[4]);
        assertEquals(2, RetentionPolicy.indexOf(RetentionPolicy.DEFAULT_MS));
    }

    @Test
    public void expirationUsesRoomHistoryStart() {
        long start = 1_000L;
        long duration = RetentionPolicy.HOUR_MS;
        assertFalse(RetentionPolicy.isExpired(start, duration, start + duration - 1));
        assertTrue(RetentionPolicy.isExpired(start, duration, start + duration));
    }

    @Test
    public void unsupportedDurationFallsBackToOneDay() {
        assertEquals(RetentionPolicy.DEFAULT_MS, RetentionPolicy.normalize(123L));
        assertEquals(2, RetentionPolicy.indexOf(123L));
    }
}

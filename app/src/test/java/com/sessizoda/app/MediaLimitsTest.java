package com.sessizoda.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaLimitsTest {
    @Test
    public void practicalMediaLimitsRemainRaised() {
        assertEquals(100L * 1024L * 1024L, CryptoBox.MAX_IMAGE_BYTES);
        assertEquals(500L * 1024L * 1024L, CryptoBox.MAX_VIDEO_BYTES);
        assertTrue(CryptoBox.MAX_VIDEO_BYTES > CryptoBox.MAX_IMAGE_BYTES);
    }
}

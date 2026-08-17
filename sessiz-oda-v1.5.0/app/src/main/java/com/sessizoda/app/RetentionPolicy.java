package com.sessizoda.app;

final class RetentionPolicy {
    static final long HOUR_MS = 60L * 60L * 1_000L;
    static final long DAY_MS = 24L * HOUR_MS;
    static final long DEFAULT_MS = DAY_MS;
    static final long[] VALUES = {
            HOUR_MS,
            6L * HOUR_MS,
            DAY_MS,
            3L * DAY_MS,
            7L * DAY_MS
    };

    private RetentionPolicy() {
    }

    static boolean isSupported(long value) {
        for (long candidate : VALUES) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }

    static long normalize(long value) {
        return isSupported(value) ? value : DEFAULT_MS;
    }

    static int indexOf(long value) {
        for (int index = 0; index < VALUES.length; index++) {
            if (VALUES[index] == value) {
                return index;
            }
        }
        return 2;
    }

    static long expiresAt(long startedAt, long duration) {
        if (startedAt <= 0) {
            return 0;
        }
        return startedAt + normalize(duration);
    }

    static boolean isExpired(long startedAt, long duration, long now) {
        long expiresAt = expiresAt(startedAt, duration);
        return expiresAt > 0 && now >= expiresAt;
    }
}

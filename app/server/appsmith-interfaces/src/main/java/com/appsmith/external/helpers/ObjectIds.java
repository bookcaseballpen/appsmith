package com.appsmith.external.helpers;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fork: generates ids in the 24-hex-character format of MongoDB ObjectIds (4 bytes seconds, 5 random bytes, 3 bytes
 * counter), without the Mongo driver. Ids keep the shape they always had, so code and clients that check or embed
 * them (for example MCP user keys) are unaffected by the move to PostgreSQL.
 */
public final class ObjectIds {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] PROCESS_UNIQUE = new byte[5];
    private static final AtomicInteger COUNTER = new AtomicInteger(RANDOM.nextInt());

    static {
        RANDOM.nextBytes(PROCESS_UNIQUE);
    }

    private ObjectIds() {}

    public static String newId() {
        final byte[] bytes = new byte[12];
        final int seconds = (int) (System.currentTimeMillis() / 1000);
        bytes[0] = (byte) (seconds >> 24);
        bytes[1] = (byte) (seconds >> 16);
        bytes[2] = (byte) (seconds >> 8);
        bytes[3] = (byte) seconds;
        System.arraycopy(PROCESS_UNIQUE, 0, bytes, 4, 5);
        final int counter = COUNTER.getAndIncrement() & 0xFFFFFF;
        bytes[9] = (byte) (counter >> 16);
        bytes[10] = (byte) (counter >> 8);
        bytes[11] = (byte) counter;
        return HexFormat.of().formatHex(bytes);
    }

    public static boolean isValid(String value) {
        if (value == null || value.length() != 24) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}

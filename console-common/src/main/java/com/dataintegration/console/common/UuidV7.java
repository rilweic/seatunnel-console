package com.dataintegration.console.common;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUID version 7 (RFC 9562): 48-bit Unix millisecond timestamp followed by random bits, so ids are
 * time-ordered and keep InnoDB primary-key inserts sequential (docs/schema.md §1).
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    public static String next() {
        return generate(System.currentTimeMillis()).toString();
    }

    static UUID generate(long epochMillis) {
        long randA = RANDOM.nextInt() & 0xFFFL;
        long msb = (epochMillis & 0xFFFF_FFFF_FFFFL) << 16 | 0x7000L | randA;
        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }
}

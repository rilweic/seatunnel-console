package com.dataintegration.console.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

class UuidV7Test {

    @Test
    void hasVersion7AndRfcVariant() {
        UUID id = UUID.fromString(UuidV7.next());

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsTimestampSoLaterIdsSortAfterEarlierOnes() {
        String earlier = UuidV7.generate(1_700_000_000_000L).toString();
        String later = UuidV7.generate(1_700_000_000_001L).toString();

        assertThat(earlier).isLessThan(later);
        assertThat(UUID.fromString(earlier).getMostSignificantBits() >>> 16).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void isUniqueAcrossManyCalls() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(UuidV7.next());
        }
        assertThat(ids).hasSize(10_000);
    }
}

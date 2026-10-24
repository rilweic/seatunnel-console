package com.dataintegration.console.server.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataintegration.console.common.ConsoleException;

import org.junit.jupiter.api.Test;

import java.util.Base64;

class SecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @Test
    void roundTripsAndUsesFreshIvEachTime() {
        SecretCipher cipher = new SecretCipher(KEY);

        String a = cipher.encrypt("{\"password\":\"p@ss\"}");
        String b = cipher.encrypt("{\"password\":\"p@ss\"}");

        assertThat(a).isNotEqualTo(b).doesNotContain("p@ss");
        assertThat(cipher.decrypt(a)).isEqualTo("{\"password\":\"p@ss\"}");
    }

    @Test
    void wrongKeyGivesActionableError() {
        String encrypted = new SecretCipher(KEY).encrypt("secret");

        assertThatThrownBy(() -> new SecretCipher(OTHER_KEY).decrypt(encrypted))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("CONSOLE_SECRET_KEY");
    }

    @Test
    void refusesMissingOrShortKey() {
        assertThatThrownBy(() -> new SecretCipher("")).hasMessageContaining("required");
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .hasMessageContaining("32 bytes");
    }
}

package com.dataintegration.console.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataintegration.console.common.ConsoleException;

import org.junit.jupiter.api.Test;

class AccountRulesTest {

    @Test
    void passwordNeedsEightCharactersWithLettersAndDigits() {
        PasswordPolicy.check("abcd1234");
        assertThatThrownBy(() -> PasswordPolicy.check("abc123")).isInstanceOf(ConsoleException.class);
        assertThatThrownBy(() -> PasswordPolicy.check("abcdefgh")).isInstanceOf(ConsoleException.class);
        assertThatThrownBy(() -> PasswordPolicy.check("12345678")).isInstanceOf(ConsoleException.class);
        assertThatThrownBy(() -> PasswordPolicy.check(null)).isInstanceOf(ConsoleException.class);
    }

    @Test
    void fiveFailuresLockTheAccountAndSuccessResetsTheCount() {
        LoginAttempts attempts = new LoginAttempts();
        for (int i = 0; i < LoginAttempts.MAX_FAILURES - 1; i++) {
            attempts.failed("Bob");
        }
        assertThat(attempts.lockedFor("bob")).isNull();
        attempts.succeeded("bob");
        for (int i = 0; i < LoginAttempts.MAX_FAILURES - 1; i++) {
            attempts.failed("bob");
        }
        assertThat(attempts.lockedFor("bob")).isNull();
        attempts.failed("BOB");
        assertThat(attempts.lockedFor("bob")).isNotNull().isLessThanOrEqualTo(LoginAttempts.LOCK);
        attempts.clear("bob");
        assertThat(attempts.lockedFor("bob")).isNull();
    }

    @Test
    void madeUpUserNamesCannotGrowTheAttemptsWithoutBound() {
        LoginAttempts attempts = new LoginAttempts();
        for (int i = 0; i < LoginAttempts.MAX_FAILURES; i++) {
            attempts.failed("admin");
        }
        for (int i = 0; i < LoginAttempts.MAX_ENTRIES * 2; i++) {
            attempts.failed("random-" + i);
        }

        assertThat(attempts.size()).isLessThanOrEqualTo(LoginAttempts.MAX_ENTRIES);
        assertThat(attempts.lockedFor("admin")).isNotNull();
    }
}

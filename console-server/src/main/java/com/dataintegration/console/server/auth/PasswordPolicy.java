package com.dataintegration.console.server.auth;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;

/** At least 8 characters with both letters and digits. */
public final class PasswordPolicy {

    private PasswordPolicy() {}

    public static void check(String password) {
        if (password == null
                || password.length() < 8
                || password.length() > 64
                || password.chars().noneMatch(Character::isLetter)
                || password.chars().noneMatch(Character::isDigit)) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "密码至少 8 位（最多 64 位），并且要同时包含字母和数字");
        }
    }
}

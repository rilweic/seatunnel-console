package com.dataintegration.console.server.crypto;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM for datasource secrets (docs/schema.md §1). The key comes from CONSOLE_SECRET_KEY
 * (base64 of 32 random bytes) and is never stored by the console; losing it makes saved secrets
 * unrecoverable. Ciphertext format: base64("v1" | 12-byte IV | ciphertext+tag).
 */
@Component
public class SecretCipher {

    private static final byte[] VERSION = "v1".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${console.secret-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "console.secret-key (CONSOLE_SECRET_KEY) is required: base64 of 32 random bytes, "
                            + "e.g. `openssl rand -base64 32`. Keep it safe; it decrypts saved datasource passwords.");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("console.secret-key is not valid base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("console.secret-key must decode to 32 bytes, got " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder()
                    .encodeToString(ByteBuffer.allocate(VERSION.length + IV_BYTES + encrypted.length)
                            .put(VERSION)
                            .put(iv)
                            .put(encrypted)
                            .array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(String ciphertext) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(ciphertext));
            byte[] version = new byte[VERSION.length];
            buffer.get(version);
            if (!java.util.Arrays.equals(version, VERSION)) {
                throw new GeneralSecurityException("unknown ciphertext version");
            }
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException | java.nio.BufferUnderflowException e) {
            throw new ConsoleException(
                    ErrorCodes.INTERNAL_ERROR,
                    "cannot decrypt a saved secret; was CONSOLE_SECRET_KEY changed? Re-enter the datasource password.");
        }
    }
}

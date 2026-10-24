package com.dataintegration.console.server.encryptkey;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.server.auth.Access;
import com.dataintegration.console.server.auth.CurrentUser;
import com.dataintegration.console.server.crypto.SecretCipher;
import com.dataintegration.console.server.persistence.entity.EncryptKey;
import com.dataintegration.console.server.persistence.mapper.EncryptKeyMapper;
import com.dataintegration.console.server.user.UserDirectory;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keys for field encryption (docs/design.md §7.11). Admins create (generate or import) and disable
 * them; everyone can pick an enabled one for a job. A key never leaves the console in any response:
 * it is decrypted only to put it into a job's config in memory at submit time.
 */
@Service
public class EncryptKeyService {

    public static final Set<String> ALGORITHMS = Set.of("AES_GCM", "AES_CBC");
    private static final Set<Integer> KEY_BYTES = Set.of(16, 24, 32);

    public record KeyView(
            String id, String name, String description, String algorithm, int keyBits, boolean enabled,
            String createdByName, String createdAt) {}

    private final EncryptKeyMapper mapper;
    private final SecretCipher cipher;
    private final UserDirectory users;
    private final SecureRandom random = new SecureRandom();

    public EncryptKeyService(EncryptKeyMapper mapper, SecretCipher cipher, UserDirectory users) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.users = users;
    }

    public List<KeyView> list() {
        List<EncryptKey> keys = mapper.selectList(new LambdaQueryWrapper<EncryptKey>().orderByDesc(EncryptKey::getCreatedAt));
        Map<String, String> names = users.names(keys.stream().map(EncryptKey::getCreatedBy).toList());
        return keys.stream().map(k -> view(k, names)).toList();
    }

    /**
     * @param imported a Base64 key to use (16, 24 or 32 bytes, an optional {@code base64:} prefix),
     *     or blank to generate one of {@code bits}
     */
    public KeyView create(String name, String description, String algorithm, Integer bits, String imported) {
        Access.requireAdmin();
        String alg = algorithm == null || algorithm.isBlank() ? "AES_GCM" : algorithm.trim().toUpperCase();
        if (!ALGORITHMS.contains(alg)) {
            throw invalid("加密算法只支持 AES_GCM 和 AES_CBC");
        }
        byte[] raw;
        if (imported != null && !imported.isBlank()) {
            String text = imported.trim().replaceFirst("^base64:", "");
            try {
                raw = Base64.getDecoder().decode(text);
            } catch (IllegalArgumentException e) {
                throw invalid("导入的密钥不是合法的 Base64");
            }
            if (!KEY_BYTES.contains(raw.length)) {
                throw invalid("导入的密钥解码后要是 16、24 或 32 字节（AES-128/192/256），现在是 " + raw.length + " 字节");
            }
        } else {
            int size = bits == null ? 256 : bits;
            if (!KEY_BYTES.contains(size / 8) || size % 8 != 0) {
                throw invalid("密钥长度只能是 128、192 或 256 位");
            }
            raw = new byte[size / 8];
            random.nextBytes(raw);
        }
        EncryptKey key = new EncryptKey();
        key.setName(name.trim());
        key.setDescription(description);
        key.setAlgorithm(alg);
        key.setKeyBits(raw.length * 8);
        key.setKeyCipher(cipher.encrypt(Base64.getEncoder().encodeToString(raw)));
        key.setEnabled(true);
        key.setCreatedBy(CurrentUser.idOrNull());
        mapper.insert(key);
        return view(require(key.getId()), users.names(List.of(String.valueOf(key.getCreatedBy()))));
    }

    public KeyView update(String id, String name, String description, boolean enabled) {
        Access.requireAdmin();
        require(id);
        mapper.update(null, new LambdaUpdateWrapper<EncryptKey>()
                .set(EncryptKey::getName, name.trim())
                .set(EncryptKey::getDescription, description)
                .set(EncryptKey::getEnabled, enabled)
                .eq(EncryptKey::getId, id));
        EncryptKey saved = require(id);
        return view(saved, users.names(List.of(String.valueOf(saved.getCreatedBy()))));
    }

    /** The key a job may use: it exists and is enabled. */
    public EncryptKey requireUsable(String id) {
        EncryptKey key = mapper.selectById(id);
        if (key == null) {
            throw invalid("加密密钥不存在：" + id);
        }
        if (!Boolean.TRUE.equals(key.getEnabled())) {
            throw invalid("加密密钥「" + key.getName() + "」已停用，请换一个密钥");
        }
        return key;
    }

    /** The Base64 key, for putting into a config at submit time only. */
    public String reveal(String id) {
        return cipher.decrypt(requireUsable(id).getKeyCipher());
    }

    private EncryptKey require(String id) {
        EncryptKey key = mapper.selectById(id);
        if (key == null) {
            throw new ConsoleException(ErrorCodes.NOT_FOUND, "加密密钥不存在：" + id);
        }
        return key;
    }

    private static KeyView view(EncryptKey k, Map<String, String> names) {
        return new KeyView(k.getId(), k.getName(), k.getDescription(), k.getAlgorithm(), k.getKeyBits(),
                Boolean.TRUE.equals(k.getEnabled()), names.get(k.getCreatedBy()), String.valueOf(k.getCreatedAt()));
    }

    private static ConsoleException invalid(String message) {
        return new ConsoleException(ErrorCodes.INVALID_ARGUMENT, message);
    }
}

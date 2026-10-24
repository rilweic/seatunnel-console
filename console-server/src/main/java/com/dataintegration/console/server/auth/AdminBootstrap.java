package com.dataintegration.console.server.auth;

import com.dataintegration.console.server.persistence.entity.SysUser;
import com.dataintegration.console.server.persistence.mapper.SysUserMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Creates the first administrator when sys_user is empty. Credentials come from
 * CONSOLE_ADMIN_USERNAME / CONSOLE_ADMIN_PASSWORD; without a password a random one is generated and
 * logged once, so a fresh install never ships a known default password.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public AdminBootstrap(
            SysUserMapper userMapper,
            PasswordEncoder passwordEncoder,
            @Value("${console.admin.username:admin}") String username,
            @Value("${console.admin.password:}") String password) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userMapper.selectCount(null) > 0) {
            return;
        }
        boolean generated = password == null || password.isBlank();
        String effectivePassword = generated ? randomPassword() : password;
        SysUser admin = new SysUser();
        admin.setUsername(username);
        admin.setDisplayName("Administrator");
        admin.setRole(ConsoleUser.ROLE_ADMIN);
        admin.setEnabled(true);
        admin.setPasswordHash(passwordEncoder.encode(effectivePassword));
        userMapper.insert(admin);
        if (generated) {
            log.warn("Created initial administrator '{}' with generated password: {}  (change it after login)",
                    username, effectivePassword);
        } else {
            log.info("Created initial administrator '{}' from configuration", username);
        }
    }

    private static String randomPassword() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

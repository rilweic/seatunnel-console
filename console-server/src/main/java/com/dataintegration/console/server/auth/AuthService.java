package com.dataintegration.console.server.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.server.persistence.entity.SysUser;
import com.dataintegration.console.server.persistence.mapper.SysUserMapper;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

@Service
public class AuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttempts loginAttempts;
    private final AuthenticationManager authenticationManager;

    public AuthService(SysUserMapper userMapper, PasswordEncoder passwordEncoder, LoginAttempts loginAttempts) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.loginAttempts = loginAttempts;
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService());
        provider.setPasswordEncoder(passwordEncoder);
        this.authenticationManager = new ProviderManager(provider);
    }

    /** Verifies credentials and returns an authentication whose principal carries no password hash. */
    public Authentication authenticate(String username, String password) {
        Duration locked = loginAttempts.lockedFor(username);
        if (locked != null) {
            throw new ConsoleException(ErrorCodes.UNAUTHENTICATED,
                    "密码错误次数过多，账号已锁定，请 " + Math.max(1, locked.toMinutes() + 1) + " 分钟后再试");
        }
        Authentication result;
        try {
            result = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        } catch (DisabledException e) {
            throw new ConsoleException(ErrorCodes.UNAUTHENTICATED, "账号已被禁用，请联系管理员");
        } catch (BadCredentialsException e) {
            loginAttempts.failed(username);
            throw new ConsoleException(ErrorCodes.UNAUTHENTICATED, "用户名或密码错误");
        }
        loginAttempts.succeeded(username);
        ConsoleUser user = ((ConsoleUser) result.getPrincipal()).withoutPassword();
        SysUser update = new SysUser();
        update.setId(user.id());
        update.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(update);
        return authenticated(user);
    }

    /**
     * Changes the current user's own password after checking the old one, and returns the
     * principal to put back into the session (the must-change flag is cleared).
     */
    public Authentication changePassword(ConsoleUser current, String oldPassword, String newPassword) {
        SysUser user = userMapper.selectById(current.id());
        if (user == null || !passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "原密码不正确");
        }
        PasswordPolicy.check(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "新密码不能和原密码相同");
        }
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPasswordHash(passwordEncoder.encode(newPassword));
        update.setMustChangePassword(false);
        update.setPasswordChangedAt(LocalDateTime.now());
        userMapper.updateById(update);
        return authenticated(new ConsoleUser(
                current.id(), current.username(), current.displayName(), current.role(), null, current.enabled(), false, Instant.now()));
    }

    private static Authentication authenticated(ConsoleUser user) {
        return UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
    }

    private UserDetailsService userDetailsService() {
        return username -> {
            SysUser user = userMapper.selectOne(
                    new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
            if (user == null) {
                throw new UsernameNotFoundException(username);
            }
            return new ConsoleUser(
                    user.getId(),
                    user.getUsername(),
                    user.getDisplayName(),
                    user.getRole(),
                    user.getPasswordHash(),
                    Boolean.TRUE.equals(user.getEnabled()),
                    Boolean.TRUE.equals(user.getMustChangePassword()),
                    Instant.now());
        };
    }
}

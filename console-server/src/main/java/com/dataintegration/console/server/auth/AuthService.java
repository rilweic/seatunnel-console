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
    private final AccountChanges accountChanges;
    private final AuthenticationManager authenticationManager;

    public AuthService(
            SysUserMapper userMapper, PasswordEncoder passwordEncoder, LoginAttempts loginAttempts, AccountChanges accountChanges) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.loginAttempts = loginAttempts;
        this.accountChanges = accountChanges;
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
        update.setPasswordHash(passwordEncoder.encode(newPassword));
        update.setMustChangePassword(false);
        update.setPasswordChangedAt(LocalDateTime.now());
        // Only over the password that was just checked: if an administrator reset it in between,
        // the reset stands (with its must-change flag) and this change is refused.
        int written = userMapper.update(update, new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, user.getId())
                .eq(SysUser::getPasswordHash, user.getPasswordHash()));
        if (written == 0) {
            throw new ConsoleException(ErrorCodes.UNAUTHENTICATED, "密码刚被管理员重置，请用新密码重新登录");
        }
        // Other sessions of this account end: they logged in before the password changed.
        accountChanges.changed(user.getId());
        // This session goes on with the account as it is now, not as the session remembered it: an
        // administrator may have changed its role or disabled it meanwhile. The time is taken before
        // reading, so a change committed after the read still counts as newer than this session.
        Instant refreshedAt = Instant.now();
        SysUser fresh = userMapper.selectById(user.getId());
        if (fresh == null || !Boolean.TRUE.equals(fresh.getEnabled())) {
            throw new ConsoleException(ErrorCodes.UNAUTHENTICATED, "账号已被禁用，请联系管理员");
        }
        // An administrator reset the password right after this change: that reset wins, and this
        // session must not outlive it with a newer time.
        if (!update.getPasswordHash().equals(fresh.getPasswordHash())) {
            throw new ConsoleException(ErrorCodes.UNAUTHENTICATED, "密码刚被管理员重置，请用新密码重新登录");
        }
        return authenticated(new ConsoleUser(
                fresh.getId(), fresh.getUsername(), fresh.getDisplayName(), fresh.getRole(), null, true,
                Boolean.TRUE.equals(fresh.getMustChangePassword()), refreshedAt));
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

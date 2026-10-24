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

import java.time.LocalDateTime;

@Service
public class AuthService {

    private final SysUserMapper userMapper;
    private final AuthenticationManager authenticationManager;

    public AuthService(SysUserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService());
        provider.setPasswordEncoder(passwordEncoder);
        this.authenticationManager = new ProviderManager(provider);
    }

    /** Verifies credentials and returns an authentication whose principal carries no password hash. */
    public Authentication authenticate(String username, String password) {
        Authentication result;
        try {
            result = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        } catch (DisabledException e) {
            throw new ConsoleException(ErrorCodes.UNAUTHENTICATED, "account is disabled");
        } catch (BadCredentialsException e) {
            throw new ConsoleException(ErrorCodes.UNAUTHENTICATED, "wrong username or password");
        }
        ConsoleUser user = ((ConsoleUser) result.getPrincipal()).withoutPassword();
        SysUser update = new SysUser();
        update.setId(user.id());
        update.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(update);
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
                    Boolean.TRUE.equals(user.getEnabled()));
        };
    }
}

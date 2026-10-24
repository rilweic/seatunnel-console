package com.dataintegration.console.server.auth;

import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.server.persistence.entity.SysUser;
import com.dataintegration.console.server.persistence.mapper.SysUserMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * Applies account changes to sessions that are already logged in: a disabled (or vanished) user is
 * logged out on the next request; a changed role or a reset password is loaded into the session.
 */
class AccountRefreshFilter extends OncePerRequestFilter {

    private final SysUserMapper userMapper;
    private final AccountChanges changes;
    private final SecurityContextRepository contextRepository;

    AccountRefreshFilter(SysUserMapper userMapper, AccountChanges changes, SecurityContextRepository contextRepository) {
        this.userMapper = userMapper;
        this.changes = changes;
        this.contextRepository = contextRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ConsoleUser current = CurrentUser.get().orElse(null);
        if (current != null && changes.changedSince(current.id(), current.authenticatedAt())) {
            // Taken before reading, so a change committed after the read is still newer than the session.
            Instant refreshedAt = Instant.now();
            SysUser user = userMapper.selectById(current.id());
            if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                SecurityConfig.writeError(response, 401, ErrorCodes.UNAUTHENTICATED, "账号已被禁用，请联系管理员");
                return;
            }
            ConsoleUser refreshed = new ConsoleUser(
                    user.getId(),
                    user.getUsername(),
                    user.getDisplayName(),
                    user.getRole(),
                    null,
                    true,
                    Boolean.TRUE.equals(user.getMustChangePassword()),
                    refreshedAt);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(refreshed, null, refreshed.getAuthorities()));
            SecurityContextHolder.setContext(context);
            contextRepository.saveContext(context, request, response);
        }
        chain.doFilter(request, response);
    }
}

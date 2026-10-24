package com.dataintegration.console.server.auth;

import com.dataintegration.console.common.ErrorCodes;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * An account still on its initial (or reset) password may only use /api/auth/** until it sets
 * its own. Enforced here so the rule holds for API calls as well as for the UI.
 */
class MustChangePasswordFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean blocked = path.startsWith("/api/")
                && !path.startsWith("/api/auth/")
                && CurrentUser.get().map(ConsoleUser::mustChangePassword).orElse(false);
        if (blocked) {
            SecurityConfig.writeError(response, 403, ErrorCodes.PASSWORD_CHANGE_REQUIRED, "请先修改初始密码");
            return;
        }
        chain.doFilter(request, response);
    }
}

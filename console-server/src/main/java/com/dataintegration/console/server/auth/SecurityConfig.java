package com.dataintegration.console.server.auth;

import com.dataintegration.console.common.ErrorCodes;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import java.io.IOException;

/**
 * Session-based login for the single-instance console (docs/design.md, schema.md Q5). The API
 * answers 401/403 as JSON instead of redirecting; the bundled SPA is public and handles login.
 * CSRF uses the cookie-to-header pattern that axios supports out of the box.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    static final String LOGIN_PATH = "/api/auth/login";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.spa().ignoringRequestMatchers(LOGIN_PATH))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(LOGIN_PATH, "/actuator/health/**")
                                        .permitAll()
                                        .requestMatchers("/api/**", "/actuator/**")
                                        .authenticated()
                                        .anyRequest()
                                        .permitAll())
                .securityContext(context -> context.securityContextRepository(securityContextRepository()))
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (request, response, e) ->
                                                        writeError(response, 401, ErrorCodes.UNAUTHENTICATED, "not logged in"))
                                        .accessDeniedHandler(
                                                (request, response, e) ->
                                                        writeError(response, 403, ErrorCodes.FORBIDDEN, "access denied")))
                .addFilterAfter(new MustChangePasswordFilter(), AuthorizationFilter.class)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(
                        logout ->
                                logout.logoutUrl("/api/auth/logout")
                                        .logoutSuccessHandler(
                                                (request, response, auth) -> response.setStatus(HttpServletResponse.SC_NO_CONTENT)));
        return http.build();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    static void writeError(HttpServletResponse response, int status, int code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}

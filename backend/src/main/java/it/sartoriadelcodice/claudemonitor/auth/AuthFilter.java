package it.sartoriadelcodice.claudemonitor.auth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import it.sartoriadelcodice.claudemonitor.config.SecurityConfig;
import it.sartoriadelcodice.claudemonitor.hookkey.HookKeyService;
import it.sartoriadelcodice.claudemonitor.user.Role;
import it.sartoriadelcodice.claudemonitor.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Legge l'header Authorization: Bearer.
 * Su /api/events il valore è una chiave hook (ruolo HOOK); altrove è il JWT del frontend (ruolo USER o ADMIN).
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    public static final String EVENTS_PATH = "/api/events";

    private static final String BEARER = "Bearer ";

    private final HookKeyService hookKeyService;
    private final JwtService jwtService;
    private final UserRepository users;

    public AuthFilter(HookKeyService hookKeyService, JwtService jwtService, UserRepository users) {
        this.hookKeyService = hookKeyService;
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            String token = header.substring(BEARER.length()).trim();
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (EVENTS_PATH.equals(path)) {
                hookKeyService.authenticate(token)
                        .ifPresent(user -> authenticate(user, List.of(SecurityConfig.ROLE_HOOK)));
            } else {
                // L'utente si rilegge a ogni richiesta: disattivazione e cambio ruolo hanno effetto subito
                jwtService.verify(token)
                        .flatMap(users::findById)
                        .filter(u -> u.isEnabled())
                        .ifPresent(u -> {
                            List<String> roles = new ArrayList<>(List.of(SecurityConfig.ROLE_USER));
                            if (u.getRole() == Role.ADMIN) {
                                roles.add(SecurityConfig.ROLE_ADMIN);
                            }
                            authenticate(new AuthUser(u.getId(), u.getUsername(), u.getRole()), roles);
                        });
            }
        }
        chain.doFilter(request, response);
    }

    private void authenticate(AuthUser principal, List<String> roles) {
        List<GrantedAuthority> authorities = roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }
}

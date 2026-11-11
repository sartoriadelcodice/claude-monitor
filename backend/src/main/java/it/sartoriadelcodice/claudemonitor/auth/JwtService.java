package it.sartoriadelcodice.claudemonitor.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import it.sartoriadelcodice.claudemonitor.config.MonitorProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Emissione e verifica dei JWT usati dal frontend. */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final MonitorProperties properties;
    private final Clock clock;

    public JwtService(MonitorProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        String secret = properties.auth().jwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            // Senza un segreto valido si usa una chiave casuale: i token decadono a ogni riavvio
            log.warn("JWT_SECRET assente o più corto di 32 byte: uso una chiave casuale valida fino al riavvio");
            this.key = Jwts.SIG.HS256.key().build();
        } else {
            this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        }
    }

    public IssuedToken issue(Long userId) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.auth().jwtTtl());
        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /** Restituisce l'id utente del token se firma e scadenza sono valide. */
    public Optional<Long> verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.ofNullable(claims.getSubject()).map(Long::valueOf);
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }
}

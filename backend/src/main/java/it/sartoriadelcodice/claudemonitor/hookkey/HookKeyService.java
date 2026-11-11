package it.sartoriadelcodice.claudemonitor.hookkey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import it.sartoriadelcodice.claudemonitor.auth.AuthUser;
import it.sartoriadelcodice.claudemonitor.user.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creazione, verifica e revoca delle chiavi hook. */
@Service
public class HookKeyService {

    /** Prefisso che rende riconoscibili le chiavi (utile anche agli scanner di segreti). */
    public static final String KEY_PREFIX = "cmk_";

    private static final int PREFIX_LENGTH = KEY_PREFIX.length() + 6;
    /** L'ultimo utilizzo si aggiorna al massimo una volta al minuto, non a ogni evento. */
    private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(1);

    private final HookKeyRepository keys;
    private final UserRepository users;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public HookKeyService(HookKeyRepository keys, UserRepository users, Clock clock) {
        this.keys = keys;
        this.users = users;
        this.clock = clock;
    }

    /** Crea una chiave: quella in chiaro è restituita solo qui e non è più recuperabile. */
    @Transactional
    public CreatedKey create(Long userId, String name) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String plain = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        HookKey key = keys.save(new HookKey(userId, name.trim(), hash(plain), plain.substring(0, PREFIX_LENGTH),
                clock.instant()));
        return new CreatedKey(HookKeyDto.from(key), plain);
    }

    /**
     * Importa come chiave un token già esistente (il vecchio HOOK_TOKEN condiviso).
     *
     * @return false se era già stato importato, anche se poi revocato
     */
    @Transactional
    public boolean importLegacyKey(Long userId, String plain, String name) {
        String hash = hash(plain);
        if (keys.existsByKeyHash(hash)) {
            return false;
        }
        String prefix = plain.substring(0, Math.min(6, plain.length()));
        keys.save(new HookKey(userId, name, hash, prefix, clock.instant()));
        return true;
    }

    /** Riconosce la chiave presentata da un hook; vuoto se sconosciuta, revocata o di un utente disattivato. */
    @Transactional
    public Optional<AuthUser> authenticate(String plain) {
        if (plain == null || plain.isBlank()) {
            return Optional.empty();
        }
        Optional<HookKey> found = keys.findByKeyHash(hash(plain)).filter(k -> !k.isRevoked());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        HookKey key = found.get();
        return users.findById(key.getUserId())
                .filter(u -> u.isEnabled())
                .map(user -> {
                    Instant now = clock.instant();
                    if (key.getLastUsedAt() == null
                            || key.getLastUsedAt().isBefore(now.minus(LAST_USED_RESOLUTION))) {
                        key.setLastUsedAt(now);
                        keys.save(key);
                    }
                    return new AuthUser(user.getId(), user.getUsername(), user.getRole());
                });
    }

    @Transactional(readOnly = true)
    public List<HookKeyDto> list(Long userId) {
        return keys.findByUserIdOrderByCreatedAtDesc(userId).stream().map(HookKeyDto::from).toList();
    }

    /** Revoca una chiave dell'utente; false se non esiste o non è sua. */
    @Transactional
    public boolean revoke(Long userId, Long keyId) {
        return keys.findByIdAndUserId(keyId, userId).map(key -> {
            if (!key.isRevoked()) {
                key.setRevokedAt(clock.instant());
                keys.save(key);
            }
            return true;
        }).orElse(false);
    }

    /** Hash SHA-256 in esadecimale. La chiave è casuale e lunga: non servono sale né funzioni lente. */
    static String hash(String plain) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(plain.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record CreatedKey(HookKeyDto key, String plainKey) {
    }
}

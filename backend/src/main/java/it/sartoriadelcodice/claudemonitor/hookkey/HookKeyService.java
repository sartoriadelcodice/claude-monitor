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

/** Creation, verification and revocation of hook keys. */
@Service
public class HookKeyService {

    /** Prefix that makes the keys recognizable (also useful to secret scanners). */
    public static final String KEY_PREFIX = "cmk_";

    private static final int PREFIX_LENGTH = KEY_PREFIX.length() + 6;
    /** The last use is updated at most once a minute, not on every event. */
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

    /** Creates a key: the plain key is returned only here and cannot be retrieved later. */
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
     * Imports an existing token as a key (the old shared HOOK_TOKEN).
     *
     * @return false if it was already imported, even if later revoked
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

    /** Recognizes the key presented by a hook; empty if unknown, revoked or belonging to a disabled user. */
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

    /** Revokes one of the user's keys; false if it does not exist or is not theirs. */
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

    /** Hex-encoded SHA-256 hash. The key is long and random: no salt or slow hash function is needed. */
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

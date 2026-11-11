package it.sartoriadelcodice.claudemonitor.hookkey;

import java.time.Instant;

/** Hook key as it appears in the list: never the plain key. */
public record HookKeyDto(Long id, String name, String prefix, Instant createdAt, Instant lastUsedAt,
                         Instant revokedAt) {

    public static HookKeyDto from(HookKey k) {
        return new HookKeyDto(k.getId(), k.getName(), k.getKeyPrefix(), k.getCreatedAt(), k.getLastUsedAt(),
                k.getRevokedAt());
    }
}

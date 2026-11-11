package it.sartoriadelcodice.claudemonitor.hookkey;

import java.time.Instant;

/** Chiave hook come appare nell'elenco: mai la chiave in chiaro. */
public record HookKeyDto(Long id, String name, String prefix, Instant createdAt, Instant lastUsedAt,
                         Instant revokedAt) {

    public static HookKeyDto from(HookKey k) {
        return new HookKeyDto(k.getId(), k.getName(), k.getKeyPrefix(), k.getCreatedAt(), k.getLastUsedAt(),
                k.getRevokedAt());
    }
}

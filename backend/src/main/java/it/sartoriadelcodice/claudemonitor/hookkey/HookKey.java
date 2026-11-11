package it.sartoriadelcodice.claudemonitor.hookkey;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Chiave hook di un utente. Si conserva solo l'hash: la chiave in chiaro non è ricostruibile. */
@Entity
@Table(name = "hook_keys")
public class HookKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String name;

    // La colonna è CHAR(64): va dichiarato a Hibernate, che per le stringhe si aspetta VARCHAR
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    /** Primi caratteri della chiave, per riconoscerla nell'elenco. */
    @Column(name = "key_prefix", nullable = false)
    private String keyPrefix;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected HookKey() {
    }

    public HookKey(Long userId, String name, String keyHash, String keyPrefix, Instant createdAt) {
        this.userId = userId;
        this.name = name;
        this.keyHash = keyHash;
        this.keyPrefix = keyPrefix;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}

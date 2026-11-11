package it.sartoriadelcodice.claudemonitor.hookkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import it.sartoriadelcodice.claudemonitor.user.Role;
import it.sartoriadelcodice.claudemonitor.user.User;
import it.sartoriadelcodice.claudemonitor.user.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Test delle chiavi hook: creazione, verifica tramite hash, revoca. */
class HookKeyServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final Long USER_ID = 5L;

    private final List<HookKey> store = new ArrayList<>();
    private final HookKeyRepository keys = mock(HookKeyRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final User user = new User("mario", "hash", Role.USER, NOW);
    private HookKeyService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(user, "id", USER_ID);
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(keys.save(any())).thenAnswer(inv -> {
            HookKey key = inv.getArgument(0);
            if (!store.contains(key)) {
                ReflectionTestUtils.setField(key, "id", (long) store.size() + 1);
                store.add(key);
            }
            return key;
        });
        when(keys.findByKeyHash(any())).thenAnswer(inv -> store.stream()
                .filter(k -> inv.getArgument(0).equals(ReflectionTestUtils.getField(k, "keyHash")))
                .findFirst());
        when(keys.existsByKeyHash(any())).thenAnswer(inv -> store.stream()
                .anyMatch(k -> inv.getArgument(0).equals(ReflectionTestUtils.getField(k, "keyHash"))));
        when(keys.findByIdAndUserId(any(), any())).thenAnswer(inv -> store.stream()
                .filter(k -> k.getId().equals(inv.getArgument(0)) && k.getUserId().equals(inv.getArgument(1)))
                .findFirst());
        service = new HookKeyService(keys, users, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createdKeyIsStoredOnlyAsHash() {
        var created = service.create(USER_ID, " MacBook ");

        assertThat(created.plainKey()).startsWith(HookKeyService.KEY_PREFIX).hasSizeGreaterThan(40);
        assertThat(created.key().name()).isEqualTo("MacBook");
        assertThat(created.plainKey()).startsWith(created.key().prefix());
        String storedHash = (String) ReflectionTestUtils.getField(store.get(0), "keyHash");
        assertThat(storedHash).hasSize(64).isEqualTo(HookKeyService.hash(created.plainKey()));
        assertThat(storedHash).doesNotContain(created.plainKey());
    }

    @Test
    void keysAreUnique() {
        assertThat(service.create(USER_ID, "a").plainKey()).isNotEqualTo(service.create(USER_ID, "b").plainKey());
    }

    @Test
    void validKeyAuthenticatesItsOwner() {
        String plain = service.create(USER_ID, "MacBook").plainKey();

        var auth = service.authenticate(plain);

        assertThat(auth).isPresent();
        assertThat(auth.get().id()).isEqualTo(USER_ID);
        assertThat(store.get(0).getLastUsedAt()).isEqualTo(NOW);
    }

    @Test
    void unknownKeyIsRejected() {
        service.create(USER_ID, "MacBook");

        assertThat(service.authenticate("cmk_sconosciuta")).isEmpty();
        assertThat(service.authenticate("")).isEmpty();
        assertThat(service.authenticate(null)).isEmpty();
    }

    @Test
    void revokedKeyIsRejected() {
        var created = service.create(USER_ID, "MacBook");

        assertThat(service.revoke(USER_ID, created.key().id())).isTrue();

        assertThat(service.authenticate(created.plainKey())).isEmpty();
    }

    @Test
    void keyOfDisabledUserIsRejected() {
        String plain = service.create(USER_ID, "MacBook").plainKey();
        user.setEnabled(false);

        assertThat(service.authenticate(plain)).isEmpty();
    }

    @Test
    void userCannotRevokeSomeoneElsesKey() {
        var created = service.create(USER_ID, "MacBook");

        assertThat(service.revoke(99L, created.key().id())).isFalse();
        assertThat(service.authenticate(created.plainKey())).isPresent();
    }

    @Test
    void legacyTokenIsImportedOnlyOnce() {
        assertThat(service.importLegacyKey(USER_ID, "vecchio-token", "HOOK_TOKEN")).isTrue();
        assertThat(service.authenticate("vecchio-token")).isPresent();

        // Dopo la revoca non viene ricreato al riavvio
        service.revoke(USER_ID, store.get(0).getId());
        assertThat(service.importLegacyKey(USER_ID, "vecchio-token", "HOOK_TOKEN")).isFalse();
        assertThat(service.authenticate("vecchio-token")).isEmpty();
    }
}

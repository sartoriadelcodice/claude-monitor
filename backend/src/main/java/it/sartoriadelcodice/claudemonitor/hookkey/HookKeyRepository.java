package it.sartoriadelcodice.claudemonitor.hookkey;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HookKeyRepository extends JpaRepository<HookKey, Long> {

    Optional<HookKey> findByKeyHash(String keyHash);

    boolean existsByKeyHash(String keyHash);

    List<HookKey> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<HookKey> findByIdAndUserId(Long id, Long userId);
}

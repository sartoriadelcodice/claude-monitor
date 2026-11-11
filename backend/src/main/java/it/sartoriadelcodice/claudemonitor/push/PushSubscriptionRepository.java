package it.sartoriadelcodice.claudemonitor.push;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    Optional<PushSubscription> findByEndpoint(String endpoint);

    List<PushSubscription> findByUserId(Long userId);

    long deleteByEndpointAndUserId(String endpoint, Long userId);

    /** Assegna a un utente le subscription create prima della gestione multiutente. */
    @Modifying
    @Query("update PushSubscription p set p.userId = :userId where p.userId is null")
    int assignOrphansTo(@Param("userId") Long userId);
}

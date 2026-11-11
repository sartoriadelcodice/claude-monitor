package it.sartoriadelcodice.claudemonitor.session;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<Session, String> {

    List<Session> findByUserIdAndArchivedFalseOrderByLastEventAtDesc(Long userId);

    List<Session> findByUserIdOrderByLastEventAtDesc(Long userId, Pageable pageable);

    Optional<Session> findByIdAndUserId(String id, Long userId);

    /** Assegna a un utente le sessioni create prima della gestione multiutente. */
    @Modifying
    @Query("update Session s set s.userId = :userId where s.userId is null")
    int assignOrphansTo(@Param("userId") Long userId);

    List<Session> findByStatusInAndLastEventAtBefore(Collection<SessionStatus> statuses, Instant threshold);
}

package it.sartoriadelcodice.claudemonitor.event;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionEventRepository extends JpaRepository<SessionEvent, Long> {

    List<SessionEvent> findBySessionIdOrderByReceivedAtDescIdDesc(String sessionId);
}

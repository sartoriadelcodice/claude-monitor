package it.sartoriadelcodice.claudemonitor.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    List<User> findAllByOrderByUsernameAsc();

    long countByRoleAndEnabledTrue(Role role);
}

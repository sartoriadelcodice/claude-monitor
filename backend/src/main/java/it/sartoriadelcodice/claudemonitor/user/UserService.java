package it.sartoriadelcodice.claudemonitor.user;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** User management: creation, changes, passwords. */
@Service
public class UserService {

    public static final int MIN_PASSWORD_LENGTH = 10;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** Checks the credentials; empty if they are wrong or the user is disabled. */
    @Transactional(readOnly = true)
    public Optional<User> authenticate(String username, String password) {
        return users.findByUsernameIgnoreCase(username.trim())
                .filter(User::isEnabled)
                .filter(u -> passwordEncoder.matches(password, u.getPasswordHash()));
    }

    @Transactional(readOnly = true)
    public List<UserDto> list() {
        return users.findAllByOrderByUsernameAsc().stream().map(UserDto::from).toList();
    }

    @Transactional
    public UserDto create(String username, String password, Role role) {
        String name = username.trim();
        if (users.existsByUsernameIgnoreCase(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already in use");
        }
        User user = new User(name, passwordEncoder.encode(password), role != null ? role : Role.USER,
                clock.instant());
        return UserDto.from(users.save(user));
    }

    /**
     * Changes a user on behalf of an administrator. Null parameters are ignored.
     * The last active administrator cannot be disabled or demoted, to avoid being locked out.
     */
    @Transactional
    public Optional<UserDto> update(Long id, String newPassword, Boolean enabled, Role role) {
        return users.findById(id).map(user -> {
            boolean losesAdmin = user.getRole() == Role.ADMIN && user.isEnabled()
                    && (Boolean.FALSE.equals(enabled) || (role != null && role != Role.ADMIN));
            if (losesAdmin && users.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "At least one active administrator must remain");
            }
            if (newPassword != null) {
                user.setPasswordHash(passwordEncoder.encode(newPassword));
            }
            if (enabled != null) {
                user.setEnabled(enabled);
            }
            if (role != null) {
                user.setRole(role);
            }
            return UserDto.from(users.save(user));
        });
    }

    /** Changes one's own password, after checking the current one. */
    @Transactional
    public void changeOwnPassword(Long userId, String currentPassword, String newPassword) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        users.save(user);
    }
}

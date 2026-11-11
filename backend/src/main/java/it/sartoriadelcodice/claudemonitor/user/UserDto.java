package it.sartoriadelcodice.claudemonitor.user;

import java.time.Instant;

public record UserDto(Long id, String username, Role role, boolean enabled, Instant createdAt) {

    public static UserDto from(User u) {
        return new UserDto(u.getId(), u.getUsername(), u.getRole(), u.isEnabled(), u.getCreatedAt());
    }
}

package it.sartoriadelcodice.claudemonitor.hookkey;

import java.time.Instant;
import java.util.List;

import it.sartoriadelcodice.claudemonitor.auth.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Hook keys of the logged-in user. */
@RestController
@RequestMapping("/api/hook-keys")
public class HookKeyController {

    private final HookKeyService hookKeyService;

    public HookKeyController(HookKeyService hookKeyService) {
        this.hookKeyService = hookKeyService;
    }

    @GetMapping
    public List<HookKeyDto> list(@AuthenticationPrincipal AuthUser user) {
        return hookKeyService.list(user.id());
    }

    /** Creates a key. The "key" field with the plain key appears only in this response. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedResponse create(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CreateRequest request) {
        var created = hookKeyService.create(user.id(), request.name());
        return new CreatedResponse(created.key().id(), created.key().name(), created.key().prefix(),
                created.key().createdAt(), created.plainKey());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        if (!hookKeyService.revoke(user.id(), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Key not found");
        }
    }

    public record CreateRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record CreatedResponse(Long id, String name, String prefix, Instant createdAt, String key) {
    }
}

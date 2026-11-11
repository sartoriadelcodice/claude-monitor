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

/** Chiavi hook dell'utente collegato. */
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

    /** Crea una chiave. Il campo "key" con la chiave in chiaro compare solo in questa risposta. */
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
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chiave non trovata");
        }
    }

    public record CreateRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record CreatedResponse(Long id, String name, String prefix, Instant createdAt, String key) {
    }
}

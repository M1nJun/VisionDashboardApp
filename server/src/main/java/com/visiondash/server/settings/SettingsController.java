package com.visiondash.server.settings;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    /** Sent by the Settings page on every write; issued by the unlock call below. */
    public static final String TOKEN_HEADER = "X-Settings-Token";

    private final SettingsService settings;
    private final SettingsAuth auth;

    public SettingsController(SettingsService settings, SettingsAuth auth) {
        this.settings = settings;
        this.auth = auth;
    }

    public record Update(String value) {
    }

    public record Unlock(String password) {
    }

    public record Unlocked(String token) {
    }

    /**
     * Exchanges the password for a token. Reading settings stays open - the values are
     * on the dashboard anyway - and only changing one needs the token.
     */
    @PostMapping("/unlock")
    public Unlocked unlock(@RequestBody Unlock request) {
        String token = auth.unlock(request == null ? null : request.password());
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "wrong password");
        }
        return new Unlocked(token);
    }

    /**
     * Says whether a token is still good.
     *
     * The page keeps its token for the tab, but the token itself expires on the server.
     * Without this the page would let someone straight in on a token the server had
     * already forgotten, and every save would then fail with a 401 they had no way to
     * read as "enter the password again".
     */
    @GetMapping("/session")
    public void session(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        if (!auth.isUnlocked(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "settings are locked");
        }
    }

    @GetMapping
    public List<SettingsService.Entry> all() {
        return settings.all();
    }

    @PutMapping("/{key}")
    public SettingsService.Entry put(@PathVariable String key, @RequestBody Update update,
                                     @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        if (!auth.isUnlocked(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "settings are locked");
        }
        if (update == null || update.value() == null || update.value().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value is required");
        }
        try {
            settings.put(key, update.value().trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        return settings.all().stream().filter(e -> e.key().equals(key)).findFirst().orElseThrow();
    }
}

package com.visiondash.server.settings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The lock on Settings.
 *
 * The password is checked here rather than in the browser so it is not sitting in the
 * JavaScript bundle for anyone who opens the developer tools. What the page holds is a
 * token this class issued, and the token is all the write endpoint will accept.
 *
 * This keeps a wrong click from retuning a threshold; it is not a security boundary, and
 * the dashboard is not on a network where it would need to be. Change the password with
 * VISIONDASH_SETTINGS_PASSWORD in run-server.cmd.
 */
@Component
public class SettingsAuth {

    private static final Duration LIFETIME = Duration.ofHours(8);

    private final String password;
    private final SecureRandom random = new SecureRandom();
    /** Tokens live in memory: a server restart simply asks for the password again. */
    private final Map<String, Instant> issued = new ConcurrentHashMap<>();

    public SettingsAuth(@Value("${visiondash.settings.password:mimi}") String password) {
        this.password = password;
    }

    /** Null when the password is wrong. */
    public String unlock(String attempt) {
        if (attempt == null || !password.equals(attempt)) {
            return null;
        }
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        issued.put(token, Instant.now().plus(LIFETIME));
        return token;
    }

    public boolean isUnlocked(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Instant expiry = issued.get(token);
        if (expiry == null) {
            return false;
        }
        if (expiry.isBefore(Instant.now())) {
            issued.remove(token);
            return false;
        }
        return true;
    }
}

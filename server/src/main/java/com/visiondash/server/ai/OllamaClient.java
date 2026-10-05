package com.visiondash.server.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.visiondash.server.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The only part of the server that talks to a language model.
 *
 * Written against java.net.http rather than a client library on purpose. Ollama's
 * generate endpoint is one POST with a JSON body, the central PC has no internet to
 * fetch a dependency from, and the offline Maven repository there holds exactly what
 * the current build needs - so a new dependency would mean shipping a jar by hand every
 * time the build machine changed. The JDK has had an HTTP client since 11.
 *
 * Ollama itself is a separate process on the same PC. That is what makes this feature
 * safe for the dashboard: the model occupies its own memory, its own GPU and its own
 * scheduler, and the worst this JVM ever does on its behalf is hold one thread waiting
 * on a socket.
 */
@Component
public class OllamaClient {
    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    /** Connecting is local and instant, or it is never going to work. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private final SettingsService settings;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public OllamaClient(SettingsService settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    /** Thrown for every failure mode - unreachable, slow, non-200, empty answer. */
    public static class OllamaException extends RuntimeException {
        OllamaException(String message, Throwable cause) {
            super(message, cause);
        }

        OllamaException(String message) {
            super(message);
        }
    }

    public record Answer(String text, String model, long latencyMs) {
    }

    public String model() {
        return settings.getString("ai_ollama_model", "gemma3:12b");
    }

    /**
     * Whether Ollama is up and holding the configured model.
     *
     * The tab asks this so an operator who has not finished the Ollama install sees
     * "the model is not loaded" instead of ten minutes of silence followed by a report
     * with no prose in it.
     */
    public boolean available() {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl() + "/api/tags"))
                            .timeout(Duration.ofSeconds(5))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return false;
            }
            JsonNode models = mapper.readTree(response.body()).path("models");
            String wanted = model();
            for (JsonNode entry : models) {
                String name = entry.path("name").asString("");
                // Ollama reports "gemma3:12b"; a tag written without one means :latest.
                if (name.equals(wanted) || name.equals(wanted + ":latest")
                        || wanted.equals(name.replace(":latest", ""))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * One non-streaming completion.
     *
     * Non-streaming because there is no one watching it arrive: the answer is written to
     * a table and read minutes later by whoever opens the tab. Streaming would buy a
     * progress bar nobody sees, at the cost of parsing a chunked protocol.
     *
     * temperature is low but not zero. This is a factual write-up of numbers that are
     * already decided, so invention is the only real risk; a little slack keeps seven
     * consecutive quiet reports from being seven identical sentences, which is how a
     * wall display stops being read.
     */
    public Answer generate(String system, String prompt) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", 0.2);
        options.put("top_p", 0.9);
        options.put("num_ctx", settings.getInt("ai_ollama_num_ctx", 8192));
        // Bounded so a model that starts repeating itself cannot hold the slot until the
        // timeout. A window's summary is a short paragraph; this is several times that.
        options.put("num_predict", 700);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model());
        body.put("prompt", prompt);
        body.put("system", system);
        body.put("stream", false);
        body.put("options", options);
        // Keeps the weights in video memory between reports. Reloading 8GB from disk
        // every ten minutes would dominate the run time and, on a card this size, is the
        // difference between a job that takes seconds and one that takes a minute.
        body.put("keep_alive", keepAlive());

        int timeout = settings.getInt("ai_ollama_timeout_seconds", 180);
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/generate"))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long latencyMs = (System.nanoTime() - started) / 1_000_000;

            if (response.statusCode() != 200) {
                throw new OllamaException("Ollama returned HTTP " + response.statusCode()
                        + ": " + abbreviate(response.body()));
            }
            String text = mapper.readTree(response.body()).path("response").asString("").trim();
            if (text.isEmpty()) {
                throw new OllamaException("Ollama returned an empty response");
            }
            log.info("AI report generated by {} in {} ms ({} chars)", model(), latencyMs, text.length());
            return new Answer(text, model(), latencyMs);
        } catch (OllamaException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OllamaException("interrupted while waiting for Ollama", e);
        } catch (Exception e) {
            throw new OllamaException("Ollama call failed: " + e.getMessage(), e);
        }
    }

    /**
     * keep_alive as Ollama's two accepted shapes, and not as a third one it rejects.
     *
     * Sent bare, "-1" is a JSON string, and Ollama parses strings as Go durations - which
     * makes the whole request fail with `missing unit in duration "-1"` and costs the
     * window its prose. Seconds are a number there; "5m" and "1h" are strings. The
     * setting is free-text because both are legitimate, so the shape is decided by what
     * the operator actually typed.
     */
    private Object keepAlive() {
        String raw = settings.getString("ai_ollama_keep_alive", "-1").trim();
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return raw;                     // "5m", "1h", "30s" - a duration, as written.
        }
    }

    /** Trailing slashes are the difference between /api/generate and //api/generate. */
    private String baseUrl() {
        String url = settings.getString("ai_ollama_url", "http://127.0.0.1:11434").trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(String value) {
        if (value == null) return "";
        return value.length() <= 300 ? value : value.substring(0, 300) + "…";
    }
}

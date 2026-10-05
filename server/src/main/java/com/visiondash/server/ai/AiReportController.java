package com.visiondash.server.ai;

import com.visiondash.server.settings.SettingsAuth;
import com.visiondash.server.settings.SettingsController;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/ai")
public class AiReportController {

    private final AiReportService reports;
    private final AiReportScheduler scheduler;
    private final SettingsAuth auth;

    public AiReportController(AiReportService reports, AiReportScheduler scheduler, SettingsAuth auth) {
        this.reports = reports;
        this.scheduler = scheduler;
        this.auth = auth;
    }

    /**
     * Whether the feature can work at all, and why not when it cannot.
     *
     * Asked by the tab before anything else. The three ways this feature is switched off
     * - migration not run, setting disabled, Ollama not installed - are all conditions an
     * operator can fix in a minute once they know which one it is, and are otherwise
     * indistinguishable from "the AI is broken".
     */
    @GetMapping("/status")
    public AiReportService.Status status() {
        return reports.status();
    }

    /** Newest first. The tab renders the first and lists the rest. */
    @GetMapping("/reports")
    public List<AiReportDto> recent(@RequestParam(defaultValue = "24") int limit) {
        return reports.recent(limit);
    }

    @GetMapping("/reports/{id}")
    public AiReportDto one(@PathVariable long id) {
        AiReportDto report = reports.byId(id);
        if (report == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such report");
        }
        return report;
    }

    public record RunAccepted(boolean started, String message) {
    }

    /**
     * Re-runs the most recently closed window, replacing its report.
     *
     * Exists for one job: proving a fresh Ollama install on the central PC without
     * waiting ten minutes to find out whether the model tag was right. Behind the
     * Settings password because it spends a minute of the GPU and overwrites a stored
     * report - both fine for whoever is doing the install, neither fine for a browser
     * left open on the floor.
     */
    @PostMapping("/run")
    public RunAccepted run(@RequestHeader(value = SettingsController.TOKEN_HEADER,
                                          required = false) String token) {
        if (!auth.isUnlocked(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "settings are locked");
        }
        if (!reports.schemaReady()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "run db\\migrate-ai-reports.sql first");
        }
        if (scheduler.busy()) {
            return new RunAccepted(false, "A report is already being generated.");
        }
        // The window that has just closed, whether or not it already has a report - a
        // re-run is exactly what this endpoint is for.
        boolean started = scheduler.submit(reports.lastClosedWindow(), true);
        return new RunAccepted(started, started
                ? "Generating a report for the window that just closed."
                : "A report is already being generated.");
    }
}

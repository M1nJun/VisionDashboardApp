package com.visiondash.server.settings;

import com.visiondash.server.catalog.VisionCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Operator-tunable values. The catalog holds the *default* thresholds; this table
 * holds the live ones and is seeded from the catalog on first start, so there is
 * still exactly one place a threshold is first written down.
 *
 * Cached because the ingest path reads a couple of these per batch and the grid
 * reads them per poll; {@link #invalidate()} runs on every write.
 */
@Service
@Order(1)   // after SchemaCheck: seeding rows into a table that does not exist is a worse error message
public class SettingsService implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final JdbcTemplate jdbc;
    private final VisionCatalog catalog;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public SettingsService(JdbcTemplate jdbc, VisionCatalog catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    @Override
    public void run(String... args) {
        catalog.thresholdProfiles().forEach((profile, t) -> {
            seed("defect_rate_warning_pct_" + profile, String.valueOf(t.warnPct()),
                    profile + " - NG rate % at or above which a cell turns yellow");
            seed("defect_rate_critical_pct_" + profile, String.valueOf(t.critPct()),
                    profile + " - NG rate % at or above which a cell turns red");
        });
        seedAiReport();
        reload();
        log.info("Settings loaded: {} keys", cache.size());
    }

    private void seed(String key, String value, String description) {
        jdbc.update("INSERT IGNORE INTO settings (setting_key, setting_value, description) VALUES (?, ?, ?)",
                key, value, description);
    }

    /**
     * The PD/BM boundary moved from whole minutes to seconds.
     *
     * Minutes were too coarse for a boundary somebody wants at exactly 180 seconds, and the
     * grid rounded them before comparing, so the switch landed at 210. A PC that already
     * has the minutes row keeps its value - converted, not reset to the default - and the
     * old row is removed so Settings does not show two knobs for one decision.
     */
    private void migrateDowntimeToSeconds() {
        String description = "Seconds. When every inspector on a line is idle, the line is PD (planned) until it has been stopped this long, and BM (breakdown) after. Counted from the last unit the line inspected, not from when it turned idle, so the idle threshold does not need to be subtracted. The grid still shows the duration in whole minutes.";
        jdbc.update("""
                INSERT IGNORE INTO settings (setting_key, setting_value, description)
                SELECT 'line_downtime_planned_max_seconds',
                       CAST(ROUND(CAST(setting_value AS DECIMAL(10,2)) * 60) AS CHAR), ?
                FROM settings WHERE setting_key = 'line_downtime_planned_max_minutes'
                """, description);
        seed("line_downtime_planned_max_seconds", "180", description);
        jdbc.update("DELETE FROM settings WHERE setting_key = 'line_downtime_planned_max_minutes'");
    }

    /**
     * Defaults for the AI Report tab.
     *
     * Written here rather than in db/migrate-ai-reports.sql so that a value and the
     * sentence explaining it live in one place, and a fresh install and a migrated one
     * cannot end up disagreeing about either. The migration creates the tables; this
     * creates the knobs.
     *
     * Every one of these is safe to leave alone. They are exposed because the two that
     * matter most - how much evidence a rise needs before it is announced, and which
     * model writes it up - are judgement calls that belong to whoever is standing on the
     * floor reading the reports, not to whoever built the feature.
     */
    private void seedAiReport() {
        seed("ai_report_enabled", "1",
                "1 to generate AI reports, 0 to stop. Turning this off leaves every other screen untouched - nothing else on the dashboard reads it.");
        seed("ai_report_interval_minutes", "30",
                "Minutes each report covers, and how often one is written. Shorter windows each see less production, so more of them come back as \"too little produced to judge\".");
        seed("ai_report_lag_seconds", "45",
                "Seconds to wait after a window closes before analysing it. Agents send in batches, so reading 10:20 at exactly 10:20 would miss the last few seconds of it.");

        // --- what "normal" is measured from -------------------------------------------
        seed("ai_report_baseline_hours", "24",
                "Hours of its own recent history each inspector's expected defect count is read from. Measured against five days of production, anything from 6 hours to 3 days predicts the next window equally well, so this is set to cover a whole lot cycle and still have enough cells at 3am.");
        seed("ai_report_spread_days", "7",
                "Days of history used to measure how far an inspector's count normally wanders between windows. Measured over 12 hours the figure jumps by a third between readings and comes out 30% too small, which makes the band too narrow and the alerts too many; a week is steady to within a few percent.");
        seed("ai_report_spread_min_days", "3",
                "Least history an inspector needs before its own wander is trusted. Below this it is judged by its vision type's figure instead - a newly deployed inspector is judged like its siblings rather than like a coin.");
        seed("ai_report_spread_max", "6",
                "Ceiling on the measured wander. The steadiest-behaving Example B DLNG inspectors measure about 5, so a figure far past that is describing a fault rather than a habit, and letting it stand would make that inspector impossible to flag.");
        seed("ai_report_spread_refresh_hours", "6",
                "How often the wander is re-measured. It barely moves and costs a week of windows to read, so it is not recomputed every report.");
        seed("ai_report_baseline_min_cells", "1500",
                "Cells that must have gone through in the baseline period before any comparison is made. Below this the report says nothing rather than comparing against a handful of cells.");
        seed("ai_report_min_inspected", "200",
                "Cells an inspector must have checked in the window before a rate is quoted for it at all. Below this the report gives counts only - \"120 checked, 1 NG\" - because a rate off a handful of cells is noise wearing a number.");

        // --- when that becomes an alert -----------------------------------------------
        seed("ai_report_alert_sigma", "3",
                "How many band-widths past its normal count an inspector has to go. Combined with the two gates below this lands at about three alerts per twelve-hour shift on this plant's real production.");
        seed("ai_report_alert_excess", "3",
                "Fewest extra defects over normal worth raising. Stops a rise that is real but too small to act on - and it is what finally silenced the single-defect threshold crossings, which were 247 of 288 of them.");
        seed("ai_report_alert_ratio", "2",
                "And how many times normal, so a busy inspector is not flagged for a rise that is large in cells and trivial in proportion.");
        seed("ai_report_confirm_windows", "2",
                "Consecutive windows outside the band before an alert is raised. One window out is what ordinary variation looks like; two in a row is a fault.");
        seed("ai_report_fast_ratio", "5",
                "Unless it is this many times normal, with the extra defects below - then the alert is raised on the first window. 103 defects where 0.9 were normal is not waiting half an hour for a second opinion.");
        seed("ai_report_fast_excess", "10",
                "Extra defects needed alongside the multiplier above for an alert to skip the wait.");
        seed("ai_report_clear_windows", "2",
                "Consecutive windows back inside the band before an alert is released. One calm window is not a repair.");
        seed("ai_popup_enabled", "1",
                "Whether a newly raised alert interrupts whoever is looking at the dashboard, on whichever tab they are on. 0 leaves the AI REPORT tab as the only place alerts appear.");
        seed("ai_report_max_findings", "12",
                "Most findings handed to the model in one report. Keeps the summary short enough to read from across the floor and the prompt small enough to stay fast.");
        seed("ai_report_retention_days", "30",
                "Days of past reports to keep before the nightly sweep removes them. Alerts that are still open are kept however old they are.");

        // --- the model ------------------------------------------------------------------
        seed("ai_ollama_url", "http://127.0.0.1:11434",
                "Where Ollama is listening. It runs on this same PC, so this is normally left alone.");
        seed("ai_ollama_model", "gemma3:12b",
                "Model tag to generate with, exactly as \"ollama list\" prints it. It must already be on this PC - there is no internet here to fetch one.");
        seed("ai_ollama_timeout_seconds", "180",
                "Seconds to wait for the model before giving up on the prose. The window's figures are saved either way; only the sentences are lost.");
        seed("ai_ollama_num_ctx", "8192",
                "Context window handed to the model. Larger costs video memory and buys nothing here - the prompt is a page of pre-computed figures, not raw data.");
        seed("ai_ollama_keep_alive", "-1",
                "How long Ollama keeps the model in video memory between reports. -1 keeps it loaded, which is what makes a job finish in seconds instead of reloading 8GB of weights every time. 0 frees the card between runs.");

        // The first rule asked how unlikely a count was under pure chance. Example B DLNG
        // swings three times as far as pure chance allows, so it called ordinary variation
        // significant and 166 of 221 windows came back as alerts. These knobs belong to
        // that rule and would now only confuse whoever reads the Settings screen.
        jdbc.update("DELETE FROM settings WHERE setting_key IN "
                + "('ai_report_spike_p_value', 'ai_report_elevated_p_value', 'ai_report_min_defects')");
    }

    public void reload() {
        Map<String, String> fresh = new ConcurrentHashMap<>();
        jdbc.query("SELECT setting_key, setting_value FROM settings",
                rs -> { fresh.put(rs.getString(1), rs.getString(2)); });
        cache.clear();
        cache.putAll(fresh);
    }

    public record Entry(String key, String value, String description) {
    }

    public List<Entry> all() {
        return jdbc.query("SELECT setting_key, setting_value, description FROM settings ORDER BY setting_key",
                (rs, i) -> new Entry(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    public void put(String key, String value) {
        int updated = jdbc.update("UPDATE settings SET setting_value = ? WHERE setting_key = ?", value, key);
        if (updated == 0) {
            throw new IllegalArgumentException("unknown setting: " + key);
        }
        cache.put(key, value);
    }

    public String getString(String key, String fallback) {
        return cache.getOrDefault(key, fallback);
    }

    public int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(cache.getOrDefault(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            log.warn("setting {} is not an integer, using {}", key, fallback);
            return fallback;
        }
    }

    public double getDouble(String key, double fallback) {
        try {
            return Double.parseDouble(cache.getOrDefault(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            log.warn("setting {} is not a number, using {}", key, fallback);
            return fallback;
        }
    }
}

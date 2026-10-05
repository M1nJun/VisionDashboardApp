package com.visiondash.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start against a database that has not had schema.sql applied.
 *
 * Without this the first thing to touch a missing table throws a raw SQL error deep in
 * startup, which on a factory PC means an operator reading a stack trace to work out
 * that one setup command was skipped. Runs before everything else that touches the DB.
 */
@Component
@Order(0)
public class SchemaCheck implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(SchemaCheck.class);

    private static final List<String> REQUIRED = List.of(
            "agents", "agent_lot_progress", "lot_counters", "lot_counter_judgement",
            "vision_rollup", "vision_rollup_judgement", "lot_history", "lot_history_judgement",
            "defect_occurrences", "defect_items", "defect_images", "alarms",
            "raw_events", "settings");

    private final JdbcTemplate jdbc;

    public SchemaCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        List<String> present = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);

        List<String> missing = new ArrayList<>();
        for (String table : REQUIRED) {
            if (present.stream().noneMatch(table::equalsIgnoreCase)) {
                missing.add(table);
            }
        }

        if (!missing.isEmpty()) {
            String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
            throw new IllegalStateException(String.format("""
                    Database '%s' is missing %d of the %d tables this server needs: %s

                    Apply the schema once, then start again:
                      mysql -u root -p %s < db\\schema.sql
                    """, database, missing.size(), REQUIRED.size(), String.join(", ", missing), database));
        }

        log.info("Schema check: all {} tables present in '{}'", REQUIRED.size(),
                jdbc.queryForObject("SELECT DATABASE()", String.class));
    }
}

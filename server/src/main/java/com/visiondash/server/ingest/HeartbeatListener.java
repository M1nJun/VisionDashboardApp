package com.visiondash.server.ingest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.visiondash.server.catalog.VisionCatalog;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Small UDP "I'm alive" packets, so a quiet inspector (running, no defects, no new
 * cells for a while) is distinguishable from a dead agent. UDP because a lost
 * heartbeat costs nothing - the next one arrives in two seconds.
 *
 * A heartbeat alone creates the agents row, so a freshly deployed agent shows as
 * IDLE rather than NOT_DEPLOYED before its line starts producing.
 */
@Component
public class HeartbeatListener implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatListener.class);
    private static final int MAX_PACKET = 2048;

    private final JdbcTemplate jdbc;
    private final VisionCatalog catalog;
    private final ObjectMapper mapper;
    private final int port;
    private volatile DatagramSocket socket;
    private volatile boolean running = true;

    public HeartbeatListener(JdbcTemplate jdbc, VisionCatalog catalog, ObjectMapper mapper,
                             @Value("${visiondash.heartbeat.port:6002}") int port) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.mapper = mapper;
        this.port = port;
    }

    @Override
    public void run(String... args) throws Exception {
        socket = new DatagramSocket(port);
        Thread thread = new Thread(this::loop, "heartbeat-listener");
        thread.setDaemon(true);
        thread.start();
        log.info("Heartbeat listener on UDP :{}", port);
    }

    private void loop() {
        byte[] buffer = new byte[MAX_PACKET];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String body = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                        StandardCharsets.UTF_8);
                apply(body, packet.getAddress().getHostAddress());
            } catch (Exception e) {
                if (running) {
                    log.warn("heartbeat receive failed: {}", e.toString());
                }
            }
        }
    }

    private void apply(String body, String sourceIp) {
        JsonNode node;
        try {
            node = mapper.readTree(body);
        } catch (Exception e) {
            log.warn("heartbeat from {} is not valid JSON", sourceIp);
            return;
        }
        String line = text(node, "line");
        String visionKey = text(node, "visionKey");
        if (line.isBlank() || visionKey.isBlank() || catalog.find(visionKey).isEmpty()) {
            log.warn("heartbeat from {} has unusable identity line={} visionKey={}", sourceIp, line, visionKey);
            return;
        }

        String agentId = VisionCatalog.agentId(line, visionKey);
        jdbc.update("""
                INSERT INTO agents (agent_id, line, vision_key, last_heartbeat_at, last_heartbeat_ip, agent_version)
                VALUES (?,?,?,?,?,?) AS new
                ON DUPLICATE KEY UPDATE
                  last_heartbeat_at = new.last_heartbeat_at,
                  last_heartbeat_ip = new.last_heartbeat_ip,
                  agent_version     = COALESCE(new.agent_version, agents.agent_version)
                """,
                agentId, line, visionKey, Timestamp.valueOf(LocalDateTime.now()), sourceIp,
                text(node, "agentVersion").isBlank() ? null : text(node, "agentVersion"));
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText().trim();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (socket != null) {
            socket.close();
        }
    }
}

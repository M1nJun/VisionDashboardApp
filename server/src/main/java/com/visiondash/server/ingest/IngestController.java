package com.visiondash.server.ingest;

import com.visiondash.server.catalog.VisionCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ingest")
public class IngestController {
    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final IngestService service;
    private final VisionCatalog catalog;

    public IngestController(IngestService service, VisionCatalog catalog) {
        this.service = service;
        this.catalog = catalog;
    }

    @PostMapping("/events")
    public EventBatch.Result events(@RequestBody EventBatch batch) {
        validate(batch);
        EventBatch.Result result = service.ingest(batch);
        if (result.replayed() > 0 || !result.rejected().isEmpty()) {
            log.info("ingest {}: accepted={} replayed={} rejected={}",
                    batch.agentId(), result.accepted(), result.replayed(), result.rejected().size());
        }
        return result;
    }

    /**
     * A bad envelope is rejected outright rather than stored under whatever identity it
     * claimed. An agent whose visionKey does not exist, or whose agentId does not match
     * the slot it says it serves, would otherwise write rows that no grid cell ever
     * looks up - which is exactly how the old system hid a misconfigured agent.
     */
    private void validate(EventBatch batch) {
        if (isBlank(batch.line()) || isBlank(batch.visionKey())) {
            throw badRequest("line and visionKey are required");
        }
        if (catalog.find(batch.visionKey()).isEmpty()) {
            throw badRequest("unknown visionKey: " + batch.visionKey());
        }
        if (!catalog.lines().contains(batch.line())) {
            throw badRequest("unknown line: " + batch.line());
        }
        if (catalog.hostIp(batch.line(), batch.visionKey()).isEmpty()) {
            throw badRequest("no PC in topology hosts " + batch.line() + "/" + batch.visionKey());
        }
        String expected = VisionCatalog.agentId(batch.line(), batch.visionKey());
        if (!isBlank(batch.agentId()) && !expected.equals(batch.agentId())) {
            throw badRequest("agentId " + batch.agentId() + " does not match " + expected);
        }
        if (batch.eventsOrEmpty().size() > EventBatch.MAX_EVENTS) {
            throw badRequest("batch exceeds " + EventBatch.MAX_EVENTS + " events");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

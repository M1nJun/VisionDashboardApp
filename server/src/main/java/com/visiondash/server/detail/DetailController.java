package com.visiondash.server.detail;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/vision")
public class DetailController {
    private final DetailService service;

    public DetailController(DetailService service) {
        this.service = service;
    }

    @GetMapping("/{line}/{visionKey}")
    public DetailDto get(@PathVariable String line, @PathVariable String visionKey) {
        try {
            return service.build(line, visionKey);
        } catch (DetailService.UnknownSlotException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * One NG in full - its measurements and its images - fetched when the operator opens
     * a row rather than shipped with every poll of the table above it.
     *
     * The occurrence is looked up against this slot, not by id alone, so a stale id from
     * another inspector 404s instead of quietly rendering somebody else's unit.
     */
    @GetMapping("/{line}/{visionKey}/defects/{occurrenceId}")
    public DetailDto.Occurrence defect(@PathVariable String line, @PathVariable String visionKey,
                                       @PathVariable long occurrenceId) {
        try {
            DetailDto.Occurrence occurrence = service.occurrence(line, visionKey, occurrenceId);
            if (occurrence == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no defect " + occurrenceId + " on " + line + "/" + visionKey);
            }
            return occurrence;
        } catch (DetailService.UnknownSlotException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}

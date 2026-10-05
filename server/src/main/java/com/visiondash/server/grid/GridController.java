package com.visiondash.server.grid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/grid")
public class GridController {
    private final GridService service;

    public GridController(GridService service) {
        this.service = service;
    }

    /**
     * @param windowMinutes look only this far back instead of at the whole lot. Scopes the
     *                      grid cells and therefore the rank panel, which is how an
     *                      operator asks "which inspector is bad right now" rather than
     *                      "which inspector has been bad since 06:00". Line output and the
     *                      headline tiles stay on the lot either way.
     */
    @GetMapping
    public GridDto get(@RequestParam(required = false) Integer windowMinutes) {
        return service.build(windowMinutes);
    }
}

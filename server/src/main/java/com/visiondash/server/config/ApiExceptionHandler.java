package com.visiondash.server.config;

import com.visiondash.server.catalog.VisionCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Turns the few domain exceptions into plain JSON, so a client never has to read an
 *  HTML error page to find out what it got wrong. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(VisionCatalog.UnknownVisionKeyException.class)
    public ResponseEntity<Map<String, String>> unknownVisionKey(VisionCatalog.UnknownVisionKeyException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}

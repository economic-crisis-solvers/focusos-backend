package com.focusos.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "focusos-api");
    }

    @GetMapping("/")
    public Map<String, String> root() {
        return Map.of("message", "FocusOS API is running.");
    }
}

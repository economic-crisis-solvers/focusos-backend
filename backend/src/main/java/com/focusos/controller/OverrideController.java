package com.focusos.controller;

import com.focusos.model.dto.OverrideDtos;
import com.focusos.repository.DistractionEventRepository;
import com.focusos.service.RealtimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class OverrideController {

    private static final Logger log = LoggerFactory.getLogger(OverrideController.class);

    private final DistractionEventRepository distractionRepo;
    private final RealtimeService realtimeService;

    public OverrideController(DistractionEventRepository distractionRepo, RealtimeService realtimeService) {
        this.distractionRepo = distractionRepo;
        this.realtimeService = realtimeService;
    }

    @PostMapping("/override")
    public OverrideDtos.OverrideResponse override(
        @RequestBody(required = false) OverrideDtos.OverrideRequest body,
        Authentication auth
    ) {
        UUID userId = UUID.fromString((String) auth.getPrincipal());

        int closed = 0;
        try {
            closed = distractionRepo.closeOpenEvents(userId, Instant.now());
        } catch (Exception e) {
            log.warn("[Override] Could not close distraction events: {}", e.getMessage());
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("focus_active", true);
            payload.put("score",        65);
            payload.put("override",     true);
            payload.put("timestamp",    Instant.now().toString());
            realtimeService.broadcast(userId.toString(), "focus_active_change", payload);
        } catch (Exception e) {
            log.warn("[Override] Realtime broadcast failed: {}", e.getMessage());
        }

        return new OverrideDtos.OverrideResponse(true,
            String.format("Override active. %d distraction event(s) closed.", closed));
    }
}

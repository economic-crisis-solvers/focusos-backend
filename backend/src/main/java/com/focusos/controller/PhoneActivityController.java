package com.focusos.controller;

import com.focusos.model.entity.UserSettings;
import com.focusos.repository.DistractionEventRepository;
import com.focusos.repository.UserSettingsRepository;
import com.focusos.service.CompositeScoreService;
import com.focusos.service.RealtimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class PhoneActivityController {

    // helper method
    private int calculatePhoneDecayScore(double minutesInForeground, String category) {
        double seconds = minutesInForeground * 60.0;

        int floor = switch (category != null ? category.toLowerCase() : "") {
            case "social"        -> 20;
            case "entertainment" -> 25;
            case "gaming"        -> 15;
            default              -> 30;
        };

        if (seconds <= 30) {
            return (int) (90 - (seconds / 30.0) * 5);
        }

        double decayElapsed = seconds - 30;
        double decayDuration = 120.0;
        double progress = Math.min(decayElapsed / decayDuration, 1.0);

        return (int) Math.max(floor, 90 - (90 - floor) * progress);
    }

    private static final Logger log = LoggerFactory.getLogger(PhoneActivityController.class);

    private static final java.util.Set<String> DISTRACTING_PACKAGES = java.util.Set.of(
        "com.instagram.android",
        "com.twitter.android",
        "com.zhiliaoapp.musically",
        "com.facebook.katana",
        "com.snapchat.android",
        "com.reddit.frontpage",
        "com.netflix.mediaclient",
        "com.google.android.youtube"
    );

    private static final double NUDGE_THRESHOLD_MINUTES = 3.0;

    private final UserSettingsRepository     settingsRepo;
    private final RealtimeService            realtimeService;
    private final DistractionEventRepository distractionRepo;
    private final CompositeScoreService      compositeScoreService;

    public PhoneActivityController(UserSettingsRepository settingsRepo,
                                   RealtimeService realtimeService,
                                   DistractionEventRepository distractionRepo,
                                   CompositeScoreService compositeScoreService) {
        this.settingsRepo          = settingsRepo;
        this.realtimeService       = realtimeService;
        this.distractionRepo       = distractionRepo;
        this.compositeScoreService = compositeScoreService;
    }

    @PostMapping("/phone-activity")
    public ResponseEntity<Map<String, Object>> receivePhoneActivity(
        @RequestBody PhoneActivityRequest body,
        Authentication auth
    ) {
        UUID userId = UUID.fromString((String) auth.getPrincipal());

        log.info("[PhoneActivity] User {} — app: {}, category: {}, minutes: {}",
            userId, body.appPackage, body.appCategory, body.minutesInForeground);

        UserSettings settings = settingsRepo.findByUserId(userId).orElse(null);
        boolean withinWorkHours = isWithinWorkHours(settings);

        Map<String, Object> response = new HashMap<>();
        response.put("received", true);
        response.put("withinWorkHours", withinWorkHours);
        response.put("nudgeSent", false);

        if (!withinWorkHours) {
            log.info("[PhoneActivity] Outside work hours — ignoring phone activity for user {}", userId);
            return ResponseEntity.ok(response);
        }

        // ── Handle "clear" signal (user closed the distracting app) ───────
        if ("clear".equals(body.appPackage)) {
            log.info("[PhoneActivity] User {} closed distracting app — clearing phone score", userId);
            int composite = compositeScoreService.clearPhoneScore(userId.toString());
            response.put("composite_score", composite);
            return ResponseEntity.ok(response);
        }

        // ── Calculate phone decay score ───────────────────────────────────
        int phoneDecayScore;
        if (body.phoneScore > 0) {
            // Use the live decaying score sent from the phone app
            phoneDecayScore = body.phoneScore;
        } else {
            // Fallback: calculate server-side decay
            phoneDecayScore = calculatePhoneDecayScore(
                body.minutesInForeground,
                body.appCategory
            );
        }

        // ── Update composite score (min of browser and phone) ────────────
        int composite = compositeScoreService.updatePhoneScore(userId.toString(), phoneDecayScore);
        response.put("composite_score", composite);

        // ── Distraction nudge logic (for DND etc.) ───────────────────────
        boolean isDistracting = DISTRACTING_PACKAGES.contains(body.appPackage)
            || "social".equals(body.appCategory)
            || "entertainment".equals(body.appCategory);

        if (isDistracting && body.minutesInForeground >= NUDGE_THRESHOLD_MINUTES) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("focus_active",          false);
            payload.put("source",                "phone");
            payload.put("app_package",           body.appPackage);
            payload.put("app_category",          body.appCategory);
            payload.put("minutes_in_foreground", body.minutesInForeground);
            payload.put("timestamp",             Instant.now().toString());

            realtimeService.broadcast(userId.toString(), "phone_distraction", payload);
            response.put("nudgeSent", true);

            log.info("[PhoneActivity] Phone distraction nudge sent for user {} — {} mins on {}",
                userId, body.minutesInForeground, body.appPackage);
        }

        return ResponseEntity.ok(response);
    }

    private boolean isWithinWorkHours(UserSettings settings) {
        if (settings == null) return true;
        String startStr = settings.getWorkHoursStart();
        String endStr   = settings.getWorkHoursEnd();
        if (startStr == null || startStr.isEmpty() || endStr == null || endStr.isEmpty()) return true;

        try {
            LocalTime now   = LocalTime.now(ZoneId.of("Asia/Kolkata"));
            LocalTime start = LocalTime.parse(startStr);
            LocalTime end   = LocalTime.parse(endStr);

            if (start.isBefore(end)) {
                return !now.isBefore(start) && !now.isAfter(end);
            } else {
                return !now.isBefore(start) || !now.isAfter(end);
            }
        } catch (Exception e) {
            log.warn("[PhoneActivity] Could not parse work hours — defaulting to active: {}", e.getMessage());
            return true;
        }
    }

    public static class PhoneActivityRequest {
        public String appPackage;
        public String appCategory;
        public double minutesInForeground;
        public int    phoneScore;  // live decaying score from the phone app (0 = not sent)
    }
}
package com.focusos.controller;

import com.focusos.model.entity.UserSettings;
import com.focusos.repository.DistractionEventRepository;
import com.focusos.repository.UserSettingsRepository;
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

/**
 * PhoneActivityController
 * -----------------------
 * Receives foreground app data from Dev B's Android UsageStatsManager.
 * If a distracting app is detected during work hours, broadcasts a
 * focus_active_change event so the phone can show a nudge notification.
 *
 * POST /api/phone-activity
 * Auth: Bearer token (same JWT as all other endpoints)
 *
 * Payload:
 * {
 *   "appPackage": "com.instagram.android",
 *   "appCategory": "social",
 *   "minutesInForeground": 4.5
 * }
 */
@RestController
@RequestMapping("/api")
public class PhoneActivityController {

    private static final Logger log = LoggerFactory.getLogger(PhoneActivityController.class);

    // Packages considered distracting — Dev B can expand this list
    private static final java.util.Set<String> DISTRACTING_PACKAGES = java.util.Set.of(
        "com.instagram.android",
        "com.twitter.android",
        "com.zhiliaoapp.musically",   // TikTok
        "com.facebook.katana",
        "com.snapchat.android",
        "com.reddit.frontpage",
        "com.netflix.mediaclient",
        "com.google.android.youtube"
    );

    // Nudge threshold — only notify if user has been on a distracting app this long
    private static final double NUDGE_THRESHOLD_MINUTES = 3.0;

    private final UserSettingsRepository     settingsRepo;
    private final RealtimeService            realtimeService;
    private final DistractionEventRepository distractionRepo;

    public PhoneActivityController(UserSettingsRepository settingsRepo,
                                   RealtimeService realtimeService,
                                   DistractionEventRepository distractionRepo) {
        this.settingsRepo    = settingsRepo;
        this.realtimeService = realtimeService;
        this.distractionRepo = distractionRepo;
    }

    @PostMapping("/phone-activity")
    public ResponseEntity<Map<String, Object>> receivePhoneActivity(
        @RequestBody PhoneActivityRequest body,
        Authentication auth
    ) {
        UUID userId = UUID.fromString((String) auth.getPrincipal());

        log.info("[PhoneActivity] User {} — app: {}, category: {}, minutes: {}",
            userId, body.appPackage, body.appCategory, body.minutesInForeground);

        // Check work hours — only act during work hours
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

        // Check if this is a distracting app and user has been on it long enough
        boolean isDistracting = DISTRACTING_PACKAGES.contains(body.appPackage)
            || "social".equals(body.appCategory)
            || "entertainment".equals(body.appCategory);

        if (isDistracting && body.minutesInForeground >= NUDGE_THRESHOLD_MINUTES) {
            // Broadcast phone_distraction event so mobile can show nudge notification
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

    // ── Work Hours Helper ─────────────────────────────────────────────────
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

    // ── Request DTO ───────────────────────────────────────────────────────
    public static class PhoneActivityRequest {
        public String appPackage;           // e.g. "com.instagram.android"
        public String appCategory;          // e.g. "social", "entertainment"
        public double minutesInForeground;  // e.g. 4.5
    }
}

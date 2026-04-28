package com.focusos.controller;

import com.focusos.model.dto.EventDtos;
import com.focusos.model.entity.DistractionEvent;
import com.focusos.model.entity.FocusEvent;
import com.focusos.model.entity.UserSettings;
import com.focusos.repository.DistractionEventRepository;
import com.focusos.repository.FocusEventRepository;
import com.focusos.repository.UserSettingsRepository;
import com.focusos.service.ContentAnalysisService;
import com.focusos.service.MlService;
import com.focusos.service.RealtimeService;
import com.focusos.service.ResidueService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    // ── Consecutive focus tracking ────────────────────────────────────────
    private final ConcurrentHashMap<String, Integer> consecutiveFocusCount = new ConcurrentHashMap<>();
    private static final int CONSECUTIVE_FOCUS_REQUIRED = 3;

    // ── Persistent nudge tracking ─────────────────────────────────────────
    private final ConcurrentHashMap<String, Integer> belowThresholdBatches = new ConcurrentHashMap<>();
    private static final int MAX_NUDGES        = 5;
    private static final int BATCHES_PER_NUDGE = 2;

    private static final List<String> NUDGE_MESSAGES = List.of(
        "Still distracted? It's been 1 minute — try closing distracting tabs.",
        "2 minutes below focus threshold. A quick reset might help.",
        "3 minutes distracted. Take a breath and get back to your work.",
        "4 minutes gone. Every minute counts — refocus now.",
        "5 minutes distracted. Last reminder — FocusOS won't keep nudging."
    );

    private final FocusEventRepository       focusEventRepo;
    private final DistractionEventRepository distractionRepo;
    private final UserSettingsRepository     settingsRepo;
    private final MlService                  mlService;
    private final RealtimeService            realtimeService;
    private final ResidueService             residueService;
    private final ContentAnalysisService     contentAnalysisService;

    public EventController(FocusEventRepository focusEventRepo,
                           DistractionEventRepository distractionRepo,
                           UserSettingsRepository settingsRepo,
                           MlService mlService,
                           RealtimeService realtimeService,
                           ResidueService residueService,
                           ContentAnalysisService contentAnalysisService) {
        this.focusEventRepo         = focusEventRepo;
        this.distractionRepo        = distractionRepo;
        this.settingsRepo           = settingsRepo;
        this.mlService              = mlService;
        this.realtimeService        = realtimeService;
        this.residueService         = residueService;
        this.contentAnalysisService = contentAnalysisService;
    }

    @PostMapping("/events")
    public EventDtos.EventResponse ingestEvents(
        @Valid @RequestBody EventDtos.EventRequest body,
        Authentication auth
    ) {
        UUID userId = UUID.fromString((String) auth.getPrincipal());

        // ── 1. Rate limit ─────────────────────────────────────────────────
        Instant oneHourAgo = Instant.now().minusSeconds(3600);
        long recentCount = focusEventRepo.countByUserIdAndTimestampAfter(userId, oneHourAgo);
        if (recentCount >= 120) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Rate limit: 120 events/hour");
        }

        // ── 2. Fetch user settings ────────────────────────────────────────
        UserSettings settings  = settingsRepo.findByUserId(userId).orElse(null);
        int          threshold = settings != null ? settings.getFocusThreshold() : 45;
        log.info("[Events] Using threshold: {} for user {}", threshold, userId);

        // ── 3. Work hours check ───────────────────────────────────────────
        boolean withinWorkHours = isWithinWorkHours(settings);
        if (!withinWorkHours) {
            log.info("[Events] Outside work hours for user {} — passive mode only", userId);
            consecutiveFocusCount.put(userId.toString(), 0);
            belowThresholdBatches.remove(userId.toString());
        }

        // ── 4. Content analysis ───────────────────────────────────────────
        String enrichedCategory = null;
        String rawUrl = body.getActiveUrl();
        if (rawUrl != null && !rawUrl.isEmpty()) {
            try {
                enrichedCategory = contentAnalysisService.analyzeUrl(
                    rawUrl,
                    body.getPageTitle(),
                    body.getPageDescription()
                );
            } catch (Exception e) {
                log.warn("[Events] Content analysis failed: {}", e.getMessage());
            }
        }

        EventDtos.SignalPayload signals = body.getSignals();
        if (enrichedCategory != null) {
            signals.setUrlCategory(enrichedCategory);
            log.info("[Events] URL category enriched: {} → {}", rawUrl, enrichedCategory);
        }

        // ── 5. ML inference ───────────────────────────────────────────────
        MlService.PredictionResult prediction = mlService.predict(signals);
        int    score = prediction.score();
        String state = prediction.state();
        // Use user's actual threshold — NOT the ML default of 45
        boolean focusActive = score >= threshold;

        // ── 6. Previous score ─────────────────────────────────────────────
        Integer prevScore = focusEventRepo
            .findTopByUserIdOrderByTimestampDesc(userId)
            .map(FocusEvent::getScore)
            .orElse(null);

        // ── 7. Save event ─────────────────────────────────────────────────
        FocusEvent event = new FocusEvent();
        event.setUserId(userId);
        event.setScore(score);
        event.setState(state);
        event.setSignals(signalsToMap(signals));
        event.setTimestamp(body.getTimestamp() != null ? body.getTimestamp() : Instant.now());
        if (body.getSessionId() != null) {
            event.setSessionId(UUID.fromString(body.getSessionId()));
        }
        focusEventRepo.save(event);

        // ── 8. Threshold crossing + recovery tracking ─────────────────────
        boolean crossedBelow   = prevScore != null && prevScore >= threshold && score < threshold;
        String  userKey        = userId.toString();
        boolean stableRecovery = false;

        if (score >= threshold) {
            int count = consecutiveFocusCount.merge(userKey, 1, Integer::sum);
            belowThresholdBatches.remove(userKey);
            if (prevScore != null && prevScore < threshold && count >= CONSECUTIVE_FOCUS_REQUIRED) {
                stableRecovery = true;
                consecutiveFocusCount.put(userKey, 0);
                log.info("[Events] Stable focus recovery for user {} after {} batches",
                    userId, CONSECUTIVE_FOCUS_REQUIRED);
            }
        } else {
            consecutiveFocusCount.put(userKey, 0);
            if (withinWorkHours) {
                belowThresholdBatches.merge(userKey, 1, Integer::sum);
            }
        }

        // ── 9. Persistent nudge (only during work hours) ──────────────────
        String nudgeMessage = null;
        if (withinWorkHours && score < threshold && !crossedBelow) {
            int batchesBelow = belowThresholdBatches.getOrDefault(userKey, 0);
            if (batchesBelow > 0 && batchesBelow % BATCHES_PER_NUDGE == 0) {
                int nudgeIndex = (batchesBelow / BATCHES_PER_NUDGE) - 1;
                if (nudgeIndex < MAX_NUDGES) {
                    nudgeMessage = NUDGE_MESSAGES.get(nudgeIndex);
                    log.info("[Events] Nudge #{} for user {}: {}",
                        nudgeIndex + 1, userId, nudgeMessage);
                }
            }
        }

        // ── 10. Distraction tracking (work hours only, productive URLs excluded) ──
        String  currentCategory = signals.getUrlCategory() == null
            ? "other" : signals.getUrlCategory().toLowerCase();
        boolean isProductiveUrl = currentCategory.equals("work")
            || currentCategory.equals("educational");

        if (withinWorkHours) {
            if (crossedBelow && !isProductiveUrl) {
                DistractionEvent distraction = new DistractionEvent();
                distraction.setUserId(userId);
                distraction.setStartedAt(Instant.now());
                distraction.setTriggerCategory(signals.getUrlCategory());
                distraction.setResidueMinutesAdded(ResidueService.residueForDistraction(0));
                distractionRepo.save(distraction);
                log.info("[Events] Focus dropped below threshold for user {}", userId);
            }
            if (stableRecovery) {
                distractionRepo.closeOpenEvents(userId, Instant.now());
                log.info("[Events] Closing distraction events for user {}", userId);
            }
        }

        // ── 11. Realtime broadcasts ───────────────────────────────────────
        String nowIso = Instant.now().toString();

        // Always broadcast score update (regardless of work hours)
        Map<String, Object> scorePayload = new HashMap<>();
        scorePayload.put("score",     score);
        scorePayload.put("state",     state);
        scorePayload.put("timestamp", nowIso);
        realtimeService.broadcast(userId.toString(), "focus_score_update", scorePayload);

        // focus_active_change ONLY during work hours — triggers DND + notification
        if (withinWorkHours && (crossedBelow || stableRecovery)) {
            Map<String, Object> activePayload = new HashMap<>();
            activePayload.put("focus_active", focusActive);
            activePayload.put("score",        score);
            activePayload.put("timestamp",    nowIso);
            if (stableRecovery) {
                activePayload.put("release_mode", "quiet");
            }
            realtimeService.broadcast(userId.toString(), "focus_active_change", activePayload);
        }

        // ── 12. Residue ───────────────────────────────────────────────────
        double residueMinutes = residueService.calculate(userId).getResidueMinutesRemaining();

        return new EventDtos.EventResponse(score, state, focusActive, residueMinutes, nudgeMessage, compositeScore, browserScore, phoneScore);
    }

    // ── Work hours check ──────────────────────────────────────────────────
    private boolean isWithinWorkHours(UserSettings settings) {
        if (settings == null) return true;
        String startStr = settings.getWorkHoursStart();
        String endStr   = settings.getWorkHoursEnd();
        if (startStr == null || startStr.isEmpty()
            || endStr == null || endStr.isEmpty()) return true;

        try {
            LocalTime now   = LocalTime.now(ZoneId.of("Asia/Kolkata"));
            LocalTime start = LocalTime.parse(startStr);
            LocalTime end   = LocalTime.parse(endStr);

            if (start.isBefore(end)) {
                return !now.isBefore(start) && !now.isAfter(end);
            } else {
                // Overnight e.g. 22:00 - 06:00
                return !now.isBefore(start) || !now.isAfter(end);
            }
        } catch (Exception e) {
            log.warn("[Events] Could not parse work hours — defaulting active: {}", e.getMessage());
            return true;
        }
    }

    private Map<String, Object> signalsToMap(EventDtos.SignalPayload s) {
        Map<String, Object> map = new HashMap<>();
        map.put("tab_switches_per_min",        s.getTabSwitchesPerMin());
        map.put("typing_mean_interval_ms",     s.getTypingMeanIntervalMs());
        map.put("typing_std_dev_ms",           s.getTypingStdDevMs());
        map.put("scroll_velocity_px_sec",      s.getScrollVelocityPxSec());
        map.put("scroll_direction_changes",    s.getScrollDirectionChanges());
        map.put("idle_flag",                   s.getIdleFlag());
        map.put("url_category",                s.getUrlCategory());
        map.put("active_minutes_this_session", s.getActiveMinutesThisSession());
        return map;
    }
}

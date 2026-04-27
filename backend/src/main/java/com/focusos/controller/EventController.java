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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    // ── Notification timing fix ───────────────────────────────────────────
    // Track consecutive above-threshold counts per user.
    // Only release notifications after 3 consecutive focused batches (90 seconds)
    // to prevent releasing during brief score spikes.
    private final ConcurrentHashMap<String, Integer> consecutiveFocusCount = new ConcurrentHashMap<>();
    private static final int CONSECUTIVE_FOCUS_REQUIRED = 3;

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
        this.focusEventRepo       = focusEventRepo;
        this.distractionRepo      = distractionRepo;
        this.settingsRepo         = settingsRepo;
        this.mlService            = mlService;
        this.realtimeService      = realtimeService;
        this.residueService       = residueService;
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

        // ── 2. Content analysis (async — enriches URL category) ───────────
        // If user is on YouTube or an ambiguous site, analyze actual content
        // This overrides the generic Chrome extension category
        String enrichedCategory = null;
        String rawUrl = body.getActiveUrl(); // new field from Chrome extension
        if (rawUrl != null && !rawUrl.isEmpty()) {
            try {
                enrichedCategory = contentAnalysisService.analyzeUrl(rawUrl);
            } catch (Exception e) {
                log.warn("[Events] Content analysis failed: {}", e.getMessage());
            }
        }

        // Use enriched category if available, otherwise use Chrome extension category
        EventDtos.SignalPayload signals = body.getSignals();
        if (enrichedCategory != null) {
            signals.setUrlCategory(enrichedCategory);
            log.info("[Events] URL category enriched: {} → {}", rawUrl, enrichedCategory);
        }

        // ── 3. ML inference ───────────────────────────────────────────────
        MlService.PredictionResult prediction = mlService.predict(signals);
        int    score = prediction.score();
        String state = prediction.state();

        // ── 4. Fetch threshold ────────────────────────────────────────────
        // focusActive MUST use the user's actual threshold, not the ML default of 45
        int threshold = settingsRepo.findByUserId(userId)
            .map(UserSettings::getFocusThreshold)
            .orElse(45);
        boolean focusActive = score >= threshold;

        // ── 5. Previous score ─────────────────────────────────────────────
        Integer prevScore = focusEventRepo
            .findTopByUserIdOrderByTimestampDesc(userId)
            .map(FocusEvent::getScore)
            .orElse(null);

        // ── 6. Save event ─────────────────────────────────────────────────
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

        // ── 7. Threshold crossing + notification timing fix ───────────────
        boolean crossedBelow = prevScore != null && prevScore >= threshold && score < threshold;

        // Notification timing fix: only trigger "focus recovered" broadcast
        // after CONSECUTIVE_FOCUS_REQUIRED batches above threshold (not on first spike)
        String userKey = userId.toString();
        boolean stableRecovery = false;

        if (score >= threshold) {
            int count = consecutiveFocusCount.merge(userKey, 1, Integer::sum);
            if (prevScore != null && prevScore < threshold && count >= CONSECUTIVE_FOCUS_REQUIRED) {
                stableRecovery = true;
                consecutiveFocusCount.put(userKey, 0);
                log.info("[Events] Stable focus recovery for user {} after {} consecutive batches",
                    userId, CONSECUTIVE_FOCUS_REQUIRED);
            }
        } else {
            // Reset consecutive count when score drops
            consecutiveFocusCount.put(userKey, 0);
        }

        if (crossedBelow) {
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
            log.info("[Events] Stable recovery confirmed — closing distraction events for user {}", userId);
        }

        // ── 8. Realtime broadcasts ────────────────────────────────────────
        String nowIso = Instant.now().toString();

        // Always broadcast score update
        Map<String, Object> scorePayload = new HashMap<>();
        scorePayload.put("score",     score);
        scorePayload.put("state",     state);
        scorePayload.put("timestamp", nowIso);
        realtimeService.broadcast(userId.toString(), "focus_score_update", scorePayload);

        // Only broadcast focus_active_change on threshold cross OR stable recovery
        if (crossedBelow || stableRecovery) {
            Map<String, Object> activePayload = new HashMap<>();
            activePayload.put("focus_active", focusActive);
            activePayload.put("score",        score);
            activePayload.put("timestamp",    nowIso);
            // Tell mobile this is a stable recovery (release notifications quietly)
            if (stableRecovery) {
                activePayload.put("release_mode", "quiet");
            }
            realtimeService.broadcast(userId.toString(), "focus_active_change", activePayload);
        }

        // ── 9. Residue ────────────────────────────────────────────────────
        double residueMinutes = residueService.calculate(userId).getResidueMinutesRemaining();

        return new EventDtos.EventResponse(score, state, focusActive, residueMinutes);
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

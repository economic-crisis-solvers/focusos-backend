package com.focusos.controller;

import com.focusos.model.dto.EventDtos;
import com.focusos.model.entity.DistractionEvent;
import com.focusos.model.entity.FocusEvent;
import com.focusos.model.entity.UserSettings;
import com.focusos.repository.DistractionEventRepository;
import com.focusos.repository.FocusEventRepository;
import com.focusos.repository.UserSettingsRepository;
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

@RestController
@RequestMapping("/api")
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    private final FocusEventRepository focusEventRepo;
    private final DistractionEventRepository distractionRepo;
    private final UserSettingsRepository settingsRepo;
    private final MlService mlService;
    private final RealtimeService realtimeService;
    private final ResidueService residueService;

    public EventController(FocusEventRepository focusEventRepo,
                           DistractionEventRepository distractionRepo,
                           UserSettingsRepository settingsRepo,
                           MlService mlService,
                           RealtimeService realtimeService,
                           ResidueService residueService) {
        this.focusEventRepo  = focusEventRepo;
        this.distractionRepo = distractionRepo;
        this.settingsRepo    = settingsRepo;
        this.mlService       = mlService;
        this.realtimeService = realtimeService;
        this.residueService  = residueService;
    }

    @PostMapping("/events")
    public EventDtos.EventResponse ingestEvents(@Valid @RequestBody EventDtos.EventRequest body, Authentication auth) {
        UUID userId = UUID.fromString((String) auth.getPrincipal());

        // 1. Rate limit
        Instant oneHourAgo = Instant.now().minusSeconds(3600);
        long recentCount = focusEventRepo.countByUserIdAndTimestampAfter(userId, oneHourAgo);
        if (recentCount >= 10000) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Rate limit: 120 events/hour");
        }

        // 2. ML inference
        MlService.PredictionResult prediction = mlService.predict(body.getSignals());
        int score = prediction.score();
        String state = prediction.state();
        boolean focusActive = prediction.focusActive();

        // 3. Fetch threshold
        int threshold = settingsRepo.findByUserId(userId)
            .map(UserSettings::getFocusThreshold)
            .orElse(45);

        // 4. Previous score
        Integer prevScore = focusEventRepo
            .findTopByUserIdOrderByTimestampDesc(userId)
            .map(FocusEvent::getScore)
            .orElse(null);

        // 5. Save event
        FocusEvent event = new FocusEvent();
        event.setUserId(userId);
        event.setScore(score);
        event.setState(state);
        event.setSignals(signalsToMap(body.getSignals()));
        if (body.getSessionId() != null) {
            event.setSessionId(UUID.fromString(body.getSessionId()));
        }
        focusEventRepo.save(event);

        // 6. Threshold crossing
        boolean crossedBelow = prevScore != null && prevScore >= threshold && score < threshold;
        boolean crossedAbove = prevScore != null && prevScore < threshold && score >= threshold;

        if (crossedBelow) {
            DistractionEvent distraction = new DistractionEvent();
            distraction.setUserId(userId);
            distraction.setStartedAt(Instant.now());
            distraction.setTriggerCategory(body.getSignals().getUrlCategory());
            distraction.setResidueMinutesAdded(ResidueService.residueForDistraction(0));
            distractionRepo.save(distraction);
            log.info("[Events] Focus dropped below threshold for user {}", userId);
        }
        if (crossedAbove) {
            distractionRepo.closeOpenEvents(userId, Instant.now());
            log.info("[Events] Focus recovered above threshold for user {}", userId);
        }

        // 7. Realtime broadcast
        String nowIso = Instant.now().toString();
        Map<String, Object> scorePayload = new HashMap<>();
        scorePayload.put("score", score);
        scorePayload.put("state", state);
        scorePayload.put("timestamp", nowIso);
        realtimeService.broadcast(userId.toString(), "focus_score_update", scorePayload);

        if (crossedBelow || crossedAbove) {
            Map<String, Object> activePayload = new HashMap<>();
            activePayload.put("focus_active", focusActive);
            activePayload.put("score", score);
            activePayload.put("timestamp", nowIso);
            realtimeService.broadcast(userId.toString(), "focus_active_change", activePayload);
        }

        // 8. Residue
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

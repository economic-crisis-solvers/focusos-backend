package com.focusos.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized in-memory cache for browser and phone focus scores.
 * Always computes composite = min(browserScore, phoneScore) so that
 * the worst-performing device drives the dashboard score.
 */
@Service
public class CompositeScoreService {

    private static final Logger log = LoggerFactory.getLogger(CompositeScoreService.class);

    private static final ConcurrentHashMap<String, Integer> browserScores = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> phoneScores   = new ConcurrentHashMap<>();

    private final RealtimeService realtimeService;

    public CompositeScoreService(RealtimeService realtimeService) {
        this.realtimeService = realtimeService;
    }

    /**
     * Update the browser score and broadcast the composite.
     * Called from EventController when ML inference produces a score.
     *
     * @return the composite score
     */
    public int updateBrowserScore(String userId, int score, String state) {
        browserScores.put(userId, score);
        int phoneScore = phoneScores.getOrDefault(userId, 100);
        int composite  = Math.min(score, phoneScore);
        String compositeState = deriveState(composite);

        log.info("[Composite] User {} — browser={}, phone={}, composite={}",
                userId, score, phoneScore, composite);

        Map<String, Object> payload = new HashMap<>();
        payload.put("score",         composite);
        payload.put("state",         compositeState);
        payload.put("source",        "composite");
        payload.put("browser_score", score);
        payload.put("phone_score",   phoneScore);
        payload.put("timestamp",     Instant.now().toString());

        realtimeService.broadcast(userId, "focus_score_update", payload);
        return composite;
    }

    /**
     * Update the phone score and broadcast the composite.
     * Called from PhoneActivityController.
     *
     * @return the composite score
     */
    public int updatePhoneScore(String userId, int score) {
        phoneScores.put(userId, score);
        int browserScore = browserScores.getOrDefault(userId, 85);
        int composite    = Math.min(browserScore, score);
        String compositeState = deriveState(composite);

        log.info("[Composite] User {} — browser={}, phone={}, composite={}",
                userId, browserScore, score, composite);

        Map<String, Object> payload = new HashMap<>();
        payload.put("score",         composite);
        payload.put("state",         compositeState);
        payload.put("source",        "composite");
        payload.put("browser_score", browserScore);
        payload.put("phone_score",   score);
        payload.put("timestamp",     Instant.now().toString());

        realtimeService.broadcast(userId, "focus_score_update", payload);
        return composite;
    }

    /**
     * Reset the phone score to 100 (user closed the distracting app).
     * Broadcasts the updated composite.
     *
     * @return the composite score after clearing
     */
    public int clearPhoneScore(String userId) {
        log.info("[Composite] User {} — phone score cleared (app closed)", userId);
        return updatePhoneScore(userId, 100);
    }

    /** Get the current composite score without updating anything. */
    public int getComposite(String userId) {
        int browser = browserScores.getOrDefault(userId, 85);
        int phone   = phoneScores.getOrDefault(userId, 100);
        return Math.min(browser, phone);
    }

    /** Get the latest cached browser score. */
    public int getBrowserScore(String userId) {
        return browserScores.getOrDefault(userId, 85);
    }

    /** Get the latest cached phone score. */
    public int getPhoneScore(String userId) {
        return phoneScores.getOrDefault(userId, 100);
    }

    private String deriveState(int score) {
        if (score < 50)  return "distracted";
        if (score < 70)  return "drifting";
        return "focused";
    }
}

package com.focusos.service;

import ai.onnxruntime.*;
import com.focusos.model.dto.EventDtos;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

@Service
public class MlService {

    private static final Logger log = LoggerFactory.getLogger(MlService.class);

    // ── Feature order — must exactly match Dev D's FEATURE_ORDER ─────────
    // ["tab_switches_per_min", "typing_mean_interval_ms", "typing_std_dev_ms",
    //  "scroll_velocity_px_sec", "scroll_direction_changes", "idle_flag",
    //  "url_category_work", "url_category_social", "url_category_entertainment",
    //  "session_minutes"]

    // ── Score anchors — from Dev D's SCORE_ANCHORS ────────────────────────
    // focused=100, drifting=50, distracted=0
    // LabelEncoder alphabetical order: 0=distracted, 1=drifting, 2=focused

    @Value("${ml.model-path:model.onnx}")
    private String modelPath;

    private OrtEnvironment env;
    private OrtSession    session;
    private boolean       modelLoaded = false;

    @PostConstruct
    public void loadModel() {
        try {
            Path path = Path.of(modelPath);
            if (!Files.exists(path)) {
                log.warn("[ML] model.onnx not found at '{}'. Using rule-based fallback.", modelPath);
                return;
            }
            env     = OrtEnvironment.getEnvironment();
            session = env.createSession(modelPath, new OrtSession.SessionOptions());
            modelLoaded = true;
            log.info("[ML] ONNX model loaded from '{}'", modelPath);
        } catch (OrtException e) {
            log.error("[ML] Failed to load ONNX model: {}. Falling back to rules.", e.getMessage());
        }
    }

    public PredictionResult predict(EventDtos.SignalPayload signals) {
        int score;
        if (modelLoaded) {
            score = runOnnxInference(signals);
        } else {
            score = ruleBased(signals);
        }
        score = Math.max(0, Math.min(100, score));
        String  state       = scoreToState(score);
        boolean focusActive = score >= 45;
        return new PredictionResult(score, state, focusActive);
    }

    // ── ONNX inference ───────────────────────────────────────────────────

    private int runOnnxInference(EventDtos.SignalPayload s) {
        try {
            float[] features  = engineerFeatures(s);
            String  inputName = session.getInputNames().iterator().next();
            OnnxTensor tensor = OnnxTensor.createTensor(env, new float[][]{features});

            try (OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
                Object output = result.get(0).getValue();

                if (output instanceof float[][] proba) {
                    // Classes in alphabetical LabelEncoder order: 0=distracted, 1=drifting, 2=focused
                    // score = P(distracted)*0 + P(drifting)*50 + P(focused)*100
                    float pDistracted = proba[0][0];
                    float pDrifting   = proba[0][1];
                    float pFocused    = proba[0][2];
                    return (int) (pDistracted * 0 + pDrifting * 50 + pFocused * 100);
                } else if (output instanceof long[] labels) {
                    // Direct class label: 0=distracted, 1=drifting, 2=focused
                    return labels[0] == 2 ? 85 : labels[0] == 1 ? 50 : 15;
                }
            }
        } catch (OrtException e) {
            log.warn("[ML] Inference error: {}. Falling back to rules.", e.getMessage());
        }
        return ruleBased(s);
    }

    // ── Feature engineering — matches Dev D's FEATURE_ORDER exactly ──────

    private float[] engineerFeatures(EventDtos.SignalPayload s) {
        String cat = s.getUrlCategory() == null ? "other" : s.getUrlCategory().toLowerCase();
        return new float[]{
            (float) s.getTabSwitchesPerMin(),           // 0: tab_switches_per_min
            (float) s.getTypingMeanIntervalMs(),         // 1: typing_mean_interval_ms
            (float) s.getTypingStdDevMs(),               // 2: typing_std_dev_ms
            (float) s.getScrollVelocityPxSec(),          // 3: scroll_velocity_px_sec
            (float) s.getScrollDirectionChanges(),       // 4: scroll_direction_changes
            (float) s.getIdleFlag(),                     // 5: idle_flag
            cat.equals("work")          ? 1f : 0f,      // 6: url_category_work
            cat.equals("social")        ? 1f : 0f,      // 7: url_category_social
            cat.equals("entertainment") ? 1f : 0f,      // 8: url_category_entertainment
            (float) s.getActiveMinutesThisSession(),     // 9: session_minutes
        };
    }

    // ── Rule-based fallback (used until model.onnx is available) ─────────

    private int ruleBased(EventDtos.SignalPayload s) {
        int score = 75;

        if (s.getTabSwitchesPerMin() > 8)      score -= 30;
        else if (s.getTabSwitchesPerMin() > 4)  score -= 15;

        String cat = s.getUrlCategory() == null ? "other" : s.getUrlCategory().toLowerCase();
        if (List.of("social","entertainment","news","shopping").contains(cat)) score -= 20;
        else if (cat.equals("work")) score += 10;

        if (s.getIdleFlag() == 1)                                                    score -= 25;
        if (s.getScrollVelocityPxSec() > 800 && s.getScrollDirectionChanges() > 10) score -= 15;
        if (s.getTypingMeanIntervalMs() == 0)                                        score -= 10;

        return score;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    public static String scoreToState(int score) {
        if (score >= 80) return "deep_focus";
        if (score >= 60) return "engaged";
        if (score >= 40) return "drifting";
        if (score >= 20) return "distracted";
        return "collapsed";
    }

    public record PredictionResult(int score, String state, boolean focusActive) {}
}

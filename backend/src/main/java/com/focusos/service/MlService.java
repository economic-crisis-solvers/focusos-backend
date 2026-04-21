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
    // 0.  tab_switches_per_min
    // 1.  typing_mean_interval_ms
    // 2.  typing_std_dev_ms
    // 3.  scroll_velocity_px_sec
    // 4.  scroll_direction_changes
    // 5.  idle_flag
    // 6.  url_category_work (one-hot)
    // 7.  url_category_social (one-hot)
    // 8.  url_category_entertainment (one-hot)
    // 9.  url_category_educational (one-hot) ← NEW
    // 10. session_minutes                    ← was 9, now 10

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

        // ── Option B: Post-processing guardrails ──────────────────────────
        // Safety net — corrects obviously wrong scores when model is uncertain.
        // Applied after inference regardless of model or rule-based path.
        score = applyGuardrails(score, signals);

        score = Math.max(0, Math.min(100, score));
        String  state       = scoreToState(score);
        boolean focusActive = score >= 45;
        return new PredictionResult(score, state, focusActive);
    }

    // ── ONNX inference ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
private int runOnnxInference(EventDtos.SignalPayload s) {
    try {
        float[] features  = engineerFeatures(s);
        String  inputName = session.getInputNames().iterator().next();
        OnnxTensor tensor = OnnxTensor.createTensor(env, new float[][]{features});

        try (OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
            // Output 0: output_label (long[]) — class label
            // Output 1: output_probability (sequence of maps) — probabilities per class
            // We need output 1 for the weighted score formula

            Object probOutput = result.get(1).getValue();

            // probOutput is List<Map<Long, Float>> — one map per sample
            if (probOutput instanceof java.util.List<?> probList && !probList.isEmpty()) {
                Object mapObj = probList.get(0);
                if (mapObj instanceof java.util.Map<?, ?> probMap) {
                    log.info("[ML] Prob map keys: {}", probMap.keySet());
    // Keys are Long (class index): 0=distracted, 1=drifting, 2=focused
                    float pDistracted = getProb(probMap, 0L);
                    float pDrifting   = getProb(probMap, 1L);
                    float pFocused    = getProb(probMap, 2L);
                    int raw = (int) (pDistracted * 0 + pDrifting * 50 + pFocused * 100);
                    log.info("[ML] ONNX score: {} (P(d)={} P(dr)={} P(f)={})",
                        raw,
                        String.format("%.2f", pDistracted),
                        String.format("%.2f", pDrifting),
                        String.format("%.2f", pFocused));
                    return raw;
                }
            }

            // Fallback: use label directly if probability parsing fails
            Object labelOutput = result.get(0).getValue();
            if (labelOutput instanceof long[] labels) {
                log.warn("[ML] Using label fallback: {}", labels[0]);
                return labels[0] == 2 ? 85 : labels[0] == 1 ? 50 : 15;
            }
        }
    } catch (OrtException e) {
        log.warn("[ML] Inference error: {}. Falling back to rules.", e.getMessage());
    }
    return ruleBased(s);
}

private float getProb(java.util.Map<?, ?> map, Long key) {
    // Try Long key first, then Integer key — ONNX Runtime Java varies by version
    Object val = map.get(key);
    if (val == null) val = map.get(key.intValue());
    if (val == null) {
        // Last resort — iterate and match by numeric value
        for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof Number n && n.longValue() == key) {
                val = entry.getValue();
                break;
            }
        }
    }
    if (val instanceof Float f) return f;
    if (val instanceof Double d) return d.floatValue();
    if (val instanceof Number n) return n.floatValue();
    return 0f;
}

    // ── Feature engineering — 11 features, matches Dev D's FEATURE_ORDER ─

    private float[] engineerFeatures(EventDtos.SignalPayload s) {
        String cat = s.getUrlCategory() == null ? "other" : s.getUrlCategory().toLowerCase();
        return new float[]{
            (float) s.getTabSwitchesPerMin(),           //  0: tab_switches_per_min
            (float) s.getTypingMeanIntervalMs(),         //  1: typing_mean_interval_ms
            (float) s.getTypingStdDevMs(),               //  2: typing_std_dev_ms
            (float) s.getScrollVelocityPxSec(),          //  3: scroll_velocity_px_sec
            (float) s.getScrollDirectionChanges(),       //  4: scroll_direction_changes
            (float) s.getIdleFlag(),                     //  5: idle_flag
            cat.equals("work")          ? 1f : 0f,      //  6: url_category_work
            cat.equals("social")        ? 1f : 0f,      //  7: url_category_social
            cat.equals("entertainment") ? 1f : 0f,      //  8: url_category_entertainment
            cat.equals("educational")   ? 1f : 0f,      //  9: url_category_educational ← NEW
            (float) s.getActiveMinutesThisSession(),     // 10: session_minutes ← was 9
        };
    }

    // ── Option B: Post-processing guardrails ──────────────────────────────
    // Corrects obviously wrong scores when ML model is uncertain.
    // These are hard rules based on domain knowledge — not ML.

    private int applyGuardrails(int score, EventDtos.SignalPayload s) {
        String cat = s.getUrlCategory() == null ? "other" : s.getUrlCategory().toLowerCase();
        double tabSwitches = s.getTabSwitchesPerMin();

        // Entertainment URL — cap score at 50 (can't be "focused" on Netflix)
        if (cat.equals("entertainment") && score > 50) {
            log.info("[ML] Guardrail: entertainment URL capped score {} → 50", score);
            score = 50;
        }

        // Social URL — cap score at 45 (social media is never deep focus)
        if (cat.equals("social") && score > 45) {
            log.info("[ML] Guardrail: social URL capped score {} → 45", score);
            score = 45;
        }

        // Educational URL — floor score at 40 (learning is never collapsed)
        if (cat.equals("educational") && score < 40) {
            log.info("[ML] Guardrail: educational URL floored score {} → 40", score);
            score = 40;
        }

        // High tab switching — cap score at 55 regardless of URL
        // (can't be deeply focused if switching tabs 8+ times per minute)
        if (tabSwitches > 8 && score > 55) {
            log.info("[ML] Guardrail: high tab switches ({}) capped score {} → 55", tabSwitches, score);
            score = Math.min(score, 55);
        }

        // Extreme tab switching — hard cap at 30
        if (tabSwitches > 12) {
            log.info("[ML] Guardrail: extreme tab switches ({}) capped score {} → 30", tabSwitches, score);
            score = Math.min(score, 30);
        }

        return score;
    }

    // ── Rule-based fallback (used when model.onnx not available) ─────────

    private int ruleBased(EventDtos.SignalPayload s) {
        int score = 75;

        if (s.getTabSwitchesPerMin() > 8)      score -= 30;
        else if (s.getTabSwitchesPerMin() > 4)  score -= 15;

        String cat = s.getUrlCategory() == null ? "other" : s.getUrlCategory().toLowerCase();
        if (List.of("social", "entertainment", "news", "shopping").contains(cat)) score -= 20;
        else if (cat.equals("work"))        score += 10;
        else if (cat.equals("educational")) score += 15;

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

    public static double residueForDistraction(double durationMinutes) {
        return 23.0;
    }

    public record PredictionResult(int score, String state, boolean focusActive) {}
}

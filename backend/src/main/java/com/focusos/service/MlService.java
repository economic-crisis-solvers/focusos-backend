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
    // 9.  url_category_educational (one-hot)
    // 10. session_minutes

    @Value("${ml.model-path:model.onnx}")
    private String modelPath;

    private OrtEnvironment env;
    private OrtSession     session;
    private boolean        modelLoaded = false;

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
        int score = modelLoaded ? runOnnxInference(signals) : ruleBased(signals);

        // Apply guardrails AFTER inference
        score = applyGuardrails(score, signals);
        score = Math.max(0, Math.min(100, score));

        String  state       = scoreToState(score);
        boolean focusActive = score >= 45; // default — EventController overrides with user threshold
        return new PredictionResult(score, state, focusActive);
    }

    // ── ONNX inference ────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int runOnnxInference(EventDtos.SignalPayload s) {
        try {
            float[]    features  = engineerFeatures(s);
            String     inputName = session.getInputNames().iterator().next();
            OnnxTensor tensor    = OnnxTensor.createTensor(env, new float[][]{features});

            try (OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {

                // Output 1 = output_probability (List<OnnxMap>)
                Object probOutput = result.get(1).getValue();

                if (probOutput instanceof java.util.List probList && !probList.isEmpty()) {
                    Object mapObj = probList.get(0);

                    // ONNX Runtime Java wraps the map in OnnxMap — must call getValue()
                    java.util.Map<?, ?> probMap = null;
                    if (mapObj instanceof OnnxMap onnxMap) {
                        Object inner = onnxMap.getValue();
                        if (inner instanceof java.util.Map<?, ?> m) probMap = m;
                    } else if (mapObj instanceof java.util.Map<?, ?> m) {
                        probMap = m;
                    }

                    if (probMap != null) {
                        // Keys are Integer in ONNX Runtime Java
                        float pDistracted = extractProb(probMap, 0);
                        float pDrifting   = extractProb(probMap, 1);
                        float pFocused    = extractProb(probMap, 2);
                        int raw = (int)(pDistracted * 0 + pDrifting * 50 + pFocused * 100);
                        log.info("[ML] ONNX score: {} P(d)={} P(dr)={} P(f)={}",
                            raw,
                            String.format("%.3f", pDistracted),
                            String.format("%.3f", pDrifting),
                            String.format("%.3f", pFocused));
                        return raw;
                    }
                }

                // Label fallback
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

    private float extractProb(java.util.Map<?, ?> map, int classIndex) {
        // Try Integer key first (ONNX Runtime Java uses Integer keys)
        Object val = map.get(classIndex);
        if (val == null) val = map.get((long) classIndex);
        if (val == null) {
            for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof Number n && n.intValue() == classIndex) {
                    val = entry.getValue();
                    break;
                }
            }
        }
        if (val instanceof Float f)  return f;
        if (val instanceof Double d) return d.floatValue();
        if (val instanceof Number n) return n.floatValue();
        return 0f;
    }

    // ── Feature engineering — 11 features ────────────────────────────────

    private float[] engineerFeatures(EventDtos.SignalPayload s) {
        String cat = s.getUrlCategory() == null ? "other" : s.getUrlCategory().toLowerCase();
        return new float[]{
            (float) s.getTabSwitchesPerMin(),           //  0
            (float) s.getTypingMeanIntervalMs(),         //  1
            (float) s.getTypingStdDevMs(),               //  2
            (float) s.getScrollVelocityPxSec(),          //  3
            (float) s.getScrollDirectionChanges(),       //  4
            (float) s.getIdleFlag(),                     //  5
            cat.equals("work")          ? 1f : 0f,      //  6
            cat.equals("social")        ? 1f : 0f,      //  7
            cat.equals("entertainment") ? 1f : 0f,      //  8
            cat.equals("educational")   ? 1f : 0f,      //  9
            (float) s.getActiveMinutesThisSession(),     // 10
        };
    }

    // ── Guardrails ────────────────────────────────────────────────────────
    // Post-processing rules that correct obviously wrong ML scores.
    // These run after every inference regardless of model or rule-based path.

    private int applyGuardrails(int score, EventDtos.SignalPayload s) {
        String cat         = s.getUrlCategory() == null ? "other" : s.getUrlCategory().toLowerCase();
        double tabSwitches = s.getTabSwitchesPerMin();
        double sessionMins = s.getActiveMinutesThisSession();
        int    idleFlag    = s.getIdleFlag();

        // ── Idle penalty ──────────────────────────────────────────────────
        // Model ignores idle_flag almost completely. We apply a manual penalty.
        // Idle = no typing, no mouse, no scroll for 90+ seconds.
        // Being idle on any site for a sustained period = not working.
        if (idleFlag == 1) {
            if (score > 60) {
                log.info("[ML] Guardrail: idle flag capped score {} → 60", score);
                score = 60;
            }
            // Additional penalty based on session time while idle
            // Being idle for a long time in a session = likely walked away or distracted
            if (sessionMins > 5 && score > 45) {
                log.info("[ML] Guardrail: idle >5min session capped score {} → 45", score);
                score = 45;
            }
            if (sessionMins > 10 && score > 35) {
                log.info("[ML] Guardrail: idle >10min session capped score {} → 35", score);
                score = 35;
            }
        }

        // ── Deep focus gate ───────────────────────────────────────────────
        // Score of 80+ (deep_focus) should only happen after sustained focus.
        // Prevents instant jump from low score to 99 on a single focused batch.
        // Require at least 2 minutes of session time before allowing deep_focus.
        if (score >= 80 && sessionMins < 2.0) {
            log.info("[ML] Guardrail: deep_focus gated — session only {}min, capping {} → 72", sessionMins, score);
            score = 72; // engaged state, not deep_focus
        }

        // ── Entertainment URL time-aware cap ──────────────────────────────
        if (cat.equals("entertainment")) {
            if (sessionMins > 5 && score > 25) {
                log.info("[ML] Guardrail: entertainment >5min capped {} → 25", score);
                score = 25;
            } else if (sessionMins > 2 && score > 35) {
                log.info("[ML] Guardrail: entertainment >2min capped {} → 35", score);
                score = 35;
            } else if (score > 50) {
                log.info("[ML] Guardrail: entertainment capped {} → 50", score);
                score = 50;
            }
        }

        // ── Social URL time-aware cap ─────────────────────────────────────
        if (cat.equals("social")) {
            if (sessionMins > 5 && score > 20) {
                log.info("[ML] Guardrail: social >5min capped {} → 20", score);
                score = 20;
            } else if (sessionMins > 2 && score > 30) {
                log.info("[ML] Guardrail: social >2min capped {} → 30", score);
                score = 30;
            } else if (score > 45) {
                log.info("[ML] Guardrail: social capped {} → 45", score);
                score = 45;
            }
        }

        // ── Educational URL cap ───────────────────────────────────────────
        // Educational is good but shouldn't always be 99.
        // No typing + educational = watching/reading = engaged, not deep focus.
        // Typing + educational = taking notes = can be deep focus.
        if (cat.equals("educational")) {
            boolean isTyping = s.getTypingMeanIntervalMs() > 50;
            if (!isTyping && score > 75) {
                log.info("[ML] Guardrail: educational no-typing capped {} → 75", score);
                score = 75; // watching/reading = engaged, not deep focus
            }
            if (score < 40) {
                log.info("[ML] Guardrail: educational floored {} → 40", score);
                score = 40; // learning is never collapsed
            }
        }

        // ── Work URL with idle ────────────────────────────────────────────
        // Work URL + completely idle = browser open but person walked away
        if (cat.equals("work") && idleFlag == 1 && score > 55) {
            log.info("[ML] Guardrail: work+idle capped {} → 55", score);
            score = 55;
        }

        // ── High tab switching ────────────────────────────────────────────
        if (tabSwitches > 12) {
            score = Math.min(score, 30);
            log.info("[ML] Guardrail: extreme tab switches capped to 30");
        } else if (tabSwitches > 8 && score > 55) {
            score = Math.min(score, 55);
            log.info("[ML] Guardrail: high tab switches capped to 55");
        }

        return score;
    }

    // ── Rule-based fallback ───────────────────────────────────────────────

    private int ruleBased(EventDtos.SignalPayload s) {
        int score = 70;

        if (s.getTabSwitchesPerMin() > 8)     score -= 30;
        else if (s.getTabSwitchesPerMin() > 4) score -= 15;

        String cat = s.getUrlCategory() == null ? "other" : s.getUrlCategory().toLowerCase();
        if (List.of("social", "entertainment").contains(cat)) score -= 20;
        else if (cat.equals("work"))        score += 15;
        else if (cat.equals("educational")) score += 10;

        if (s.getIdleFlag() == 1)                                                    score -= 25;
        if (s.getScrollVelocityPxSec() > 800 && s.getScrollDirectionChanges() > 10) score -= 15;
        if (s.getTypingMeanIntervalMs() == 0)                                        score -= 10;

        return score;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

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

package com.focusos.controller;

import com.focusos.model.dto.ScoreDtos;
import com.focusos.repository.FocusEventRepository;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ScoreController {

    private final FocusEventRepository focusEventRepo;

    public ScoreController(FocusEventRepository focusEventRepo) {
        this.focusEventRepo = focusEventRepo;
    }

    @GetMapping("/score/live")
    public ScoreDtos.LiveScoreResponse getLiveScore(Authentication auth) {
        UUID userId = UUID.fromString((String) auth.getPrincipal());
        return focusEventRepo.findTopByUserIdOrderByTimestampDesc(userId)
            .map(e -> new ScoreDtos.LiveScoreResponse(e.getScore(), e.getState(), e.getTimestamp()))
            .orElse(new ScoreDtos.LiveScoreResponse(0, "unknown", null));
    }

    @GetMapping("/score/history")
    public List<ScoreDtos.HistoryPoint> getHistory(
        @RequestParam(defaultValue = "7")    int    days,
        @RequestParam(defaultValue = "hour") String granularity,
        Authentication auth
    ) {
        UUID userId = UUID.fromString((String) auth.getPrincipal());
        if (!List.of("minute","hour","day").contains(granularity)) granularity = "hour";

        List<Object[]> rows = focusEventRepo.getHistory(userId, granularity, days);
        List<ScoreDtos.HistoryPoint> result = new ArrayList<>();

        for (Object[] row : rows) {
            try {
                Instant period = null;
                if (row[0] != null) {
                    // Handles both Timestamp and OffsetDateTime from Postgres
                    Object raw = row[0];
                    if (raw instanceof java.sql.Timestamp ts) {
                        period = ts.toInstant();
                    } else if (raw instanceof java.time.OffsetDateTime odt) {
                        period = odt.toInstant();
                    } else {
                        period = Instant.parse(raw.toString());
                    }
                }
                int    avgScore = row[1] != null ? ((Number) row[1]).intValue()  : 0;
                String state    = row[2] != null ? row[2].toString()             : "unknown";
                result.add(new ScoreDtos.HistoryPoint(period, avgScore, state));
            } catch (Exception e) {
                // skip malformed row
            }
        }
        return result;
    }
}

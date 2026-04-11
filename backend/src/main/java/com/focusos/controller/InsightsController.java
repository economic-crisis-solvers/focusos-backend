package com.focusos.controller;

import com.focusos.model.dto.InsightDtos;
import com.focusos.repository.DistractionEventRepository;
import com.focusos.repository.FocusEventRepository;
import com.focusos.service.ResidueService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class InsightsController {

    private final DistractionEventRepository distractionRepo;
    private final FocusEventRepository       focusEventRepo;
    private final ResidueService             residueService;

    public InsightsController(DistractionEventRepository distractionRepo,
                               FocusEventRepository focusEventRepo,
                               ResidueService residueService) {
        this.distractionRepo = distractionRepo;
        this.focusEventRepo  = focusEventRepo;
        this.residueService  = residueService;
    }

    @GetMapping("/insights")
    public InsightDtos.InsightsResponse getInsights(
        @RequestParam(defaultValue = "7") int days,
        Authentication auth
    ) {
        UUID userId = UUID.fromString((String) auth.getPrincipal());

        // Top distraction categories
        List<InsightDtos.DistractionStat> topDistractions = new ArrayList<>();
        try {
            List<Object[]> rows = distractionRepo.getTopDistractions(userId, days);
            for (Object[] row : rows) {
                topDistractions.add(new InsightDtos.DistractionStat(
                    row[0] != null ? row[0].toString() : "unknown",
                    row[1] != null ? ((Number) row[1]).longValue()   : 0L,
                    row[2] != null ? ((Number) row[2]).doubleValue() : 0.0
                ));
            }
        } catch (Exception e) {
            // no distraction data yet
        }

        // Peak focus hours
        List<InsightDtos.PeakHour> peakHours = new ArrayList<>();
        try {
            List<Object[]> rows = focusEventRepo.getPeakHours(userId, days);
            for (Object[] row : rows) {
                peakHours.add(new InsightDtos.PeakHour(
                    row[0] != null ? ((Number) row[0]).intValue() : 0,
                    row[1] != null ? ((Number) row[1]).intValue() : 0
                ));
            }
        } catch (Exception e) {
            // no focus data yet
        }

        // Residue stats
        InsightDtos.ResidueStats residueStats = residueService.calculate(userId);

        return new InsightDtos.InsightsResponse(topDistractions, peakHours, residueStats);
    }
}

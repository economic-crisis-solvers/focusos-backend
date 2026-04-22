package com.focusos.service;

import com.focusos.model.dto.InsightDtos;
import com.focusos.repository.DistractionEventRepository;
import com.focusos.repository.FocusEventRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * ResidueService
 * --------------
 * Calculates two dashboard metrics:
 *
 * 1. "Protected today" — minutes of productive time saved by FocusOS catching
 *    distractions. Formula: closed distraction events today × 8 minutes each.
 *    Rationale: each caught + recovered distraction saves ~8 minutes of work
 *    time that would have been lost. Believable range: 0-80 min per day.
 *
 * 2. "Residue remaining" — focus debt from unresolved distractions today.
 *    Formula: open (unresolved) distraction events today × 5 minutes each.
 *    Rationale: each unresolved distraction still costs ~5 min of reduced focus.
 *    Believable range: 0-25 min.
 *
 * Both numbers are intentionally conservative — believable to a normal person.
 */
@Service
public class ResidueService {

    // Minutes of productive time saved per caught + resolved distraction
    private static final double MINUTES_PROTECTED_PER_DISTRACTION = 8.0;

    // Minutes of focus debt per unresolved distraction
    private static final double MINUTES_RESIDUE_PER_OPEN_DISTRACTION = 5.0;

    private final DistractionEventRepository distractionRepo;
    private final FocusEventRepository focusEventRepo;

    public ResidueService(DistractionEventRepository distractionRepo,
                          FocusEventRepository focusEventRepo) {
        this.distractionRepo = distractionRepo;
        this.focusEventRepo  = focusEventRepo;
    }

    public InsightDtos.ResidueStats calculate(UUID userId) {
        long closedDistractions = 0L;
        long openDistractions   = 0L;

        try {
            Object[] stats = distractionRepo.getResidueStats(userId);
            if (stats != null) {
                // getResidueStats returns total count and total residue
                // We need to split into closed vs open
                closedDistractions = distractionRepo.countClosedDistractionsToday(userId);
                openDistractions   = distractionRepo.countOpenDistractionsToday(userId);
            }
        } catch (Exception e) {
            // No distraction events yet — defaults are fine
        }

        // Protected today = closed distractions × 8 minutes
        // Each time FocusOS caught a distraction and user recovered = 8 min saved
        double minutesProtected = closedDistractions * MINUTES_PROTECTED_PER_DISTRACTION;

        // Residue remaining = open distractions × 5 minutes
        // Each unresolved distraction = 5 min of reduced focus still to recover
        double residueRemaining = openDistractions * MINUTES_RESIDUE_PER_OPEN_DISTRACTION;

        long totalDistractions = closedDistractions + openDistractions;

        return new InsightDtos.ResidueStats(
            Math.round(residueRemaining * 10.0) / 10.0,
            Math.round(minutesProtected * 10.0) / 10.0,
            totalDistractions
        );
    }

    /**
     * Called from EventController when opening a new distraction event.
     * Returns how many residue minutes to add — kept at flat 23 for DB storage
     * (legacy field) but display is now calculated differently above.
     */
    public static double residueForDistraction(double durationMinutes) {
        return 23.0; // kept for DB compatibility, not used in display
    }
}

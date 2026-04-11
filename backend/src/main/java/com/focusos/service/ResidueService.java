package com.focusos.service;

import com.focusos.model.dto.InsightDtos;
import com.focusos.repository.DistractionEventRepository;
import com.focusos.repository.FocusEventRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ResidueService {

    private static final double RECOVERY_MINUTES_PER_DISTRACTION = 23.0;

    private final DistractionEventRepository distractionRepo;
    private final FocusEventRepository focusEventRepo;

    public ResidueService(DistractionEventRepository distractionRepo, FocusEventRepository focusEventRepo) {
        this.distractionRepo = distractionRepo;
        this.focusEventRepo  = focusEventRepo;
    }

    public InsightDtos.ResidueStats calculate(UUID userId) {
        long   distractionCount = 0L;
        double totalResidue     = 0.0;

        try {
            Object result = distractionRepo.getResidueStats(userId);
            if (result instanceof Object[] row) {
                distractionCount = row[0] != null ? ((Number) row[0]).longValue()   : 0L;
                totalResidue     = row[1] != null ? ((Number) row[1]).doubleValue() : 0.0;
            }
        } catch (Exception e) {
            // No distraction events yet — defaults are fine
        }

        double minutesProtected = 0.0;
        try {
            minutesProtected = focusEventRepo.sumFocusedMinutesToday(userId);
        } catch (Exception e) {
            // No focus events yet
        }

        double residueRemaining = Math.max(0.0, totalResidue - minutesProtected);

        return new InsightDtos.ResidueStats(
            Math.round(residueRemaining  * 10.0) / 10.0,
            Math.round(minutesProtected  * 10.0) / 10.0,
            distractionCount
        );
    }

    public static double residueForDistraction(double durationMinutes) {
        if (durationMinutes > 10) {
            return RECOVERY_MINUTES_PER_DISTRACTION + (durationMinutes - 10) * 0.5;
        }
        return RECOVERY_MINUTES_PER_DISTRACTION;
    }
}

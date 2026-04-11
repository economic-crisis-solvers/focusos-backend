package com.focusos.model.dto;

import java.util.List;

public class InsightDtos {

    public static class DistractionStat {
        private String triggerCategory;
        private long eventCount;
        private double totalResidue;

        public DistractionStat(String triggerCategory, long eventCount, double totalResidue) {
            this.triggerCategory = triggerCategory;
            this.eventCount = eventCount;
            this.totalResidue = totalResidue;
        }

        public String getTriggerCategory() { return triggerCategory; }
        public long getEventCount() { return eventCount; }
        public double getTotalResidue() { return totalResidue; }
    }

    public static class PeakHour {
        private int hour;
        private int avgScore;

        public PeakHour(int hour, int avgScore) {
            this.hour = hour;
            this.avgScore = avgScore;
        }

        public int getHour() { return hour; }
        public int getAvgScore() { return avgScore; }
    }

    public static class ResidueStats {
        private double residueMinutesRemaining;
        private double minutesProtected;
        private long distractionCount;

        public ResidueStats(double residueMinutesRemaining, double minutesProtected, long distractionCount) {
            this.residueMinutesRemaining = residueMinutesRemaining;
            this.minutesProtected = minutesProtected;
            this.distractionCount = distractionCount;
        }

        public double getResidueMinutesRemaining() { return residueMinutesRemaining; }
        public double getMinutesProtected() { return minutesProtected; }
        public long getDistractionCount() { return distractionCount; }
    }

    public static class InsightsResponse {
        private List<DistractionStat> topDistractions;
        private List<PeakHour> peakFocusHours;
        private ResidueStats residueStats;

        public InsightsResponse(List<DistractionStat> topDistractions, List<PeakHour> peakFocusHours, ResidueStats residueStats) {
            this.topDistractions = topDistractions;
            this.peakFocusHours = peakFocusHours;
            this.residueStats = residueStats;
        }

        public List<DistractionStat> getTopDistractions() { return topDistractions; }
        public List<PeakHour> getPeakFocusHours() { return peakFocusHours; }
        public ResidueStats getResidueStats() { return residueStats; }
    }
}

package com.focusos.model.dto;

import java.time.Instant;

public class ScoreDtos {

    public static class LiveScoreResponse {
        private int score;
        private String state;
        private Instant timestamp;

        public LiveScoreResponse(int score, String state, Instant timestamp) {
            this.score = score;
            this.state = state;
            this.timestamp = timestamp;
        }

        public int getScore() { return score; }
        public String getState() { return state; }
        public Instant getTimestamp() { return timestamp; }
    }

    public static class HistoryPoint {
        private Instant period;
        private int avgScore;
        private String state;

        public HistoryPoint(Instant period, int avgScore, String state) {
            this.period = period;
            this.avgScore = avgScore;
            this.state = state;
        }

        public Instant getPeriod() { return period; }
        public int getAvgScore() { return avgScore; }
        public String getState() { return state; }
    }
}

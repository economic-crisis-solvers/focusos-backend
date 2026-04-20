package com.focusos.model.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public class EventDtos {

    public static class SignalPayload {
        private double tabSwitchesPerMin      = 0.0;
        private double typingMeanIntervalMs   = 0.0;
        private double typingStdDevMs         = 0.0;
        private double scrollVelocityPxSec    = 0.0;
        private double scrollDirectionChanges = 0.0;
        private int    idleFlag               = 0;
        private String urlCategory            = "other";
        private double activeMinutesThisSession = 0.0;

        public double getTabSwitchesPerMin() { return tabSwitchesPerMin; }
        public void setTabSwitchesPerMin(double v) { this.tabSwitchesPerMin = v; }
        public double getTypingMeanIntervalMs() { return typingMeanIntervalMs; }
        public void setTypingMeanIntervalMs(double v) { this.typingMeanIntervalMs = v; }
        public double getTypingStdDevMs() { return typingStdDevMs; }
        public void setTypingStdDevMs(double v) { this.typingStdDevMs = v; }
        public double getScrollVelocityPxSec() { return scrollVelocityPxSec; }
        public void setScrollVelocityPxSec(double v) { this.scrollVelocityPxSec = v; }
        public double getScrollDirectionChanges() { return scrollDirectionChanges; }
        public void setScrollDirectionChanges(double v) { this.scrollDirectionChanges = v; }
        public int getIdleFlag() { return idleFlag; }
        public void setIdleFlag(int v) { this.idleFlag = v; }
        public String getUrlCategory() { return urlCategory; }
        public void setUrlCategory(String v) { this.urlCategory = v; }
        public double getActiveMinutesThisSession() { return activeMinutesThisSession; }
        public void setActiveMinutesThisSession(double v) { this.activeMinutesThisSession = v; }
    }

    public static class EventRequest {
        private String sessionId;
        private Instant timestamp;
        private String activeUrl;     // full URL of active tab
        private String pageTitle;     // document.title from Chrome extension
        private String pageDescription; // meta description from Chrome extension

        @NotNull
        private SignalPayload signals;
        private int windowCount = 6;

        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
        public String getActiveUrl() { return activeUrl; }
        public void setActiveUrl(String activeUrl) { this.activeUrl = activeUrl; }
        public String getPageTitle() { return pageTitle; }
        public void setPageTitle(String pageTitle) { this.pageTitle = pageTitle; }
        public String getPageDescription() { return pageDescription; }
        public void setPageDescription(String pageDescription) { this.pageDescription = pageDescription; }
        public SignalPayload getSignals() { return signals; }
        public void setSignals(SignalPayload signals) { this.signals = signals; }
        public int getWindowCount() { return windowCount; }
        public void setWindowCount(int windowCount) { this.windowCount = windowCount; }
    }

    public static class EventResponse {
        private int score;
        private String state;
        private boolean focusActive;
        private double residueMinutes;

        public EventResponse(int score, String state, boolean focusActive, double residueMinutes) {
            this.score = score;
            this.state = state;
            this.focusActive = focusActive;
            this.residueMinutes = residueMinutes;
        }

        public int getScore() { return score; }
        public String getState() { return state; }
        public boolean isFocusActive() { return focusActive; }
        public double getResidueMinutes() { return residueMinutes; }
    }
}

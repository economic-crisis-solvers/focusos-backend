package com.focusos.model.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public class EventDtos {

    public static class SignalPayload {
        private double tabSwitchesPerMin        = 0.0;
        private double typingMeanIntervalMs     = 0.0;
        private double typingStdDevMs           = 0.0;
        private double scrollVelocityPxSec      = 0.0;
        private double scrollDirectionChanges   = 0.0;
        private int    idleFlag                 = 0;
        private String urlCategory              = "other";
        private double activeMinutesThisSession = 0.0;

        public double getTabSwitchesPerMin()        { return tabSwitchesPerMin; }
        public void   setTabSwitchesPerMin(double v){ this.tabSwitchesPerMin = v; }
        public double getTypingMeanIntervalMs()     { return typingMeanIntervalMs; }
        public void   setTypingMeanIntervalMs(double v){ this.typingMeanIntervalMs = v; }
        public double getTypingStdDevMs()           { return typingStdDevMs; }
        public void   setTypingStdDevMs(double v)   { this.typingStdDevMs = v; }
        public double getScrollVelocityPxSec()      { return scrollVelocityPxSec; }
        public void   setScrollVelocityPxSec(double v){ this.scrollVelocityPxSec = v; }
        public double getScrollDirectionChanges()   { return scrollDirectionChanges; }
        public void   setScrollDirectionChanges(double v){ this.scrollDirectionChanges = v; }
        public int    getIdleFlag()                 { return idleFlag; }
        public void   setIdleFlag(int v)            { this.idleFlag = v; }
        public String getUrlCategory()              { return urlCategory; }
        public void   setUrlCategory(String v)      { this.urlCategory = v; }
        public double getActiveMinutesThisSession() { return activeMinutesThisSession; }
        public void   setActiveMinutesThisSession(double v){ this.activeMinutesThisSession = v; }
    }

    public static class EventRequest {
        private String  sessionId;
        private Instant timestamp;
        private String  activeUrl;
        private String  pageTitle;        // document.title from Chrome extension DOM
        private String  pageDescription;  // meta description from Chrome extension DOM

        @NotNull
        private SignalPayload signals;
        private int windowCount = 6;

        public String  getSessionId()         { return sessionId; }
        public void    setSessionId(String v) { this.sessionId = v; }
        public Instant getTimestamp()         { return timestamp; }
        public void    setTimestamp(Instant v){ this.timestamp = v; }
        public String  getActiveUrl()         { return activeUrl; }
        public void    setActiveUrl(String v) { this.activeUrl = v; }
        public String  getPageTitle()         { return pageTitle; }
        public void    setPageTitle(String v) { this.pageTitle = v; }
        public String  getPageDescription()   { return pageDescription; }
        public void    setPageDescription(String v){ this.pageDescription = v; }
        public SignalPayload getSignals()      { return signals; }
        public void    setSignals(SignalPayload v){ this.signals = v; }
        public int     getWindowCount()       { return windowCount; }
        public void    setWindowCount(int v)  { this.windowCount = v; }
    }

    public static class EventResponse {
        private int     score;
        private String  state;
        private boolean focusActive;
        private double  residueMinutes;
        private String  nudgeMessage;  // non-null only when a persistent nudge should fire
        private int     compositeScore; // min(browser, phone) — the real dashboard score
        private int     browserScore;
        private int     phoneScore;

        public EventResponse(int score, String state, boolean focusActive,
                             double residueMinutes, String nudgeMessage,
                             int compositeScore, int browserScore, int phoneScore) {
            this.score          = score;
            this.state          = state;
            this.focusActive    = focusActive;
            this.residueMinutes = residueMinutes;
            this.nudgeMessage   = nudgeMessage;
            this.compositeScore = compositeScore;
            this.browserScore   = browserScore;
            this.phoneScore     = phoneScore;
        }

        public int     getScore()          { return score; }
        public String  getState()          { return state; }
        public boolean isFocusActive()     { return focusActive; }
        public double  getResidueMinutes() { return residueMinutes; }
        public String  getNudgeMessage()   { return nudgeMessage; }
        public int     getCompositeScore() { return compositeScore; }
        public int     getBrowserScore()   { return browserScore; }
        public int     getPhoneScore()     { return phoneScore; }
    }
}

package com.focusos.model.dto;

import java.util.List;

public class SettingsDtos {

    public static class QuietHours {
        private String start = "22:00";
        private String end   = "07:00";

        public String getStart() { return start; }
        public void setStart(String start) { this.start = start; }
        public String getEnd() { return end; }
        public void setEnd(String end) { this.end = end; }
    }

    public static class SettingsResponse {
        private int focusThreshold;
        private List<String> whitelist;
        private QuietHours quietHours;

        public SettingsResponse(int focusThreshold, List<String> whitelist, QuietHours quietHours) {
            this.focusThreshold = focusThreshold;
            this.whitelist = whitelist;
            this.quietHours = quietHours;
        }

        public int getFocusThreshold() { return focusThreshold; }
        public List<String> getWhitelist() { return whitelist; }
        public QuietHours getQuietHours() { return quietHours; }
    }

    public static class SettingsUpdateRequest {
        private Integer focusThreshold;
        private List<String> whitelist;
        private QuietHours quietHours;

        public Integer getFocusThreshold() { return focusThreshold; }
        public void setFocusThreshold(Integer focusThreshold) { this.focusThreshold = focusThreshold; }
        public List<String> getWhitelist() { return whitelist; }
        public void setWhitelist(List<String> whitelist) { this.whitelist = whitelist; }
        public QuietHours getQuietHours() { return quietHours; }
        public void setQuietHours(QuietHours quietHours) { this.quietHours = quietHours; }
    }
}

package com.focusos.model.dto;

public class OverrideDtos {

    public static class OverrideRequest {
        private String reason;

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public static class OverrideResponse {
        private boolean success;
        private String message;

        public OverrideResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
    }
}

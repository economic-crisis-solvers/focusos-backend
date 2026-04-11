package com.focusos.model.dto;

import jakarta.validation.constraints.*;

public class AuthDtos {

    public static class RegisterRequest {
        @Email(message = "Invalid email") @NotBlank
        private String email;
        @NotBlank
        private String name;
        @Size(min = 6, message = "Password must be at least 6 characters") @NotBlank
        private String password;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class LoginRequest {
        @Email @NotBlank private String email;
        @NotBlank private String password;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class AuthResponse {
        private String userId;
        private String accessToken;

        public AuthResponse(String userId, String accessToken) {
            this.userId = userId;
            this.accessToken = accessToken;
        }

        public String getUserId() { return userId; }
        public String getAccessToken() { return accessToken; }
    }
}

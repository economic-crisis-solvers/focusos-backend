package com.focusos.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;

@Component
public class JwtUtil {

    private final SecretKey supabaseKey;

    public JwtUtil(
        @Value("${supabase.jwt-secret}") String supabaseJwtSecret
    ) {
        // Supabase JWT secret is Base64 encoded — decode it first
        byte[] keyBytes = Base64.getDecoder().decode(supabaseJwtSecret);
        this.supabaseKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Extract user ID (sub claim) from a Supabase JWT token.
     * Supabase tokens use HS256 and the sub claim is the user's UUID.
     */
    public String extractUserId(String token) {
        return Jwts.parser()
            .verifyWith(supabaseKey)
            .build()
            .parseSignedClaims(token)
            .getPayload()
            .getSubject();
    }

    public boolean isValid(String token) {
        try {
            extractUserId(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    // Keep this for backwards compatibility during transition
    // Remove after all clients switch to Supabase Auth
    public String generateToken(String userId) {
        return "deprecated-use-supabase-auth";
    }
}

package com.focusos.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class JwtUtil {

    private final SecretKey customKey;
    private final SecretKey supabaseKeyBase64;
    private final SecretKey supabaseKeyRaw;

    public JwtUtil(
        @Value("${jwt.secret}") String jwtSecret,
        @Value("${supabase.jwt-secret}") String supabaseJwtSecret
    ) {
        this.customKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));

        // Try base64 decoded
        SecretKey base64Key = null;
        try {
            byte[] keyBytes = Base64.getDecoder().decode(supabaseJwtSecret);
            base64Key = Keys.hmacShaKeyFor(keyBytes);
        } catch (Exception e) {
            // ignore
        }
        this.supabaseKeyBase64 = base64Key;

        // Also try raw bytes
        this.supabaseKeyRaw = Keys.hmacShaKeyFor(
            supabaseJwtSecret.getBytes(StandardCharsets.UTF_8)
        );
    }

    public String extractUserId(String token) {
        // Try Supabase base64 key
        if (supabaseKeyBase64 != null) {
            try {
                return Jwts.parser().verifyWith(supabaseKeyBase64).build()
                    .parseSignedClaims(token).getPayload().getSubject();
            } catch (JwtException ignored) {}
        }

        // Try Supabase raw key
        try {
            return Jwts.parser().verifyWith(supabaseKeyRaw).build()
                .parseSignedClaims(token).getPayload().getSubject();
        } catch (JwtException ignored) {}

        // Fall back to custom JWT
        return Jwts.parser().verifyWith(customKey).build()
            .parseSignedClaims(token).getPayload().getSubject();
    }

    public boolean isValid(String token) {
        try {
            extractUserId(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String generateToken(String userId) {
        return "use-supabase-auth";
    }
}

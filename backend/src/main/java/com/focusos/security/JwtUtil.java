package com.focusos.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class JwtUtil {

    private final SecretKey customKey;
    private final String supabaseUrl;
    private PublicKey supabasePublicKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public JwtUtil(
        @Value("${jwt.secret}") String jwtSecret,
        @Value("${supabase.url}") String supabaseUrl
    ) {
        this.customKey  = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.supabaseUrl = supabaseUrl;
        loadSupabasePublicKey();
    }

    private void loadSupabasePublicKey() {
        try {
            // Fetch JWKS from Supabase
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(supabaseUrl + "/auth/v1/.well-known/jwks.json"))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // Parse JWKS
            Map<?, ?> jwks = mapper.readValue(response.body(), Map.class);
            var keys = (java.util.List<?>) jwks.get("keys");
            if (keys != null && !keys.isEmpty()) {
                Map<?, ?> key = (Map<?, ?>) keys.get(0);
                String x5c = null;

                // Try x5c certificate first
                var x5cList = (java.util.List<?>) key.get("x5c");
                if (x5cList != null && !x5cList.isEmpty()) {
                    x5c = (String) x5cList.get(0);
                    byte[] certBytes = Base64.getDecoder().decode(x5c);
                    java.security.cert.CertificateFactory cf =
                        java.security.cert.CertificateFactory.getInstance("X.509");
                    java.security.cert.Certificate cert =
                        cf.generateCertificate(new java.io.ByteArrayInputStream(certBytes));
                    supabasePublicKey = cert.getPublicKey();
                    System.out.println("[JWT] Supabase public key loaded from JWKS (x5c)");
                }
            }
        } catch (Exception e) {
            System.err.println("[JWT] Could not load Supabase public key: " + e.getMessage());
            System.err.println("[JWT] Will fall back to custom JWT only");
        }
    }

    public String extractUserId(String token) {
        // Try Supabase ES256 public key first
        if (supabasePublicKey != null) {
            try {
                return Jwts.parser()
                    .verifyWith(supabasePublicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
            } catch (JwtException ignored) {}
        }

        // Fall back to custom HMAC JWT
        return Jwts.parser()
            .verifyWith(customKey)
            .build()
            .parseSignedClaims(token)
            .getPayload()
            .getSubject();
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

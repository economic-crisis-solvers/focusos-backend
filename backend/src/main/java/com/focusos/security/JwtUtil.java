package com.focusos.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class JwtUtil {

    private final SecretKey customKey;
    private final String supabaseUrl;
    private PublicKey supabasePublicKey = null;
    private final ObjectMapper mapper = new ObjectMapper();

    public JwtUtil(
        @Value("${jwt.secret}") String jwtSecret,
        @Value("${supabase.url}") String supabaseUrl
    ) {
        this.customKey   = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.supabaseUrl = supabaseUrl;
        loadSupabasePublicKey();
    }

    @SuppressWarnings("unchecked")
    private void loadSupabasePublicKey() {
        try {
            String jwksUrl = supabaseUrl + "/auth/v1/.well-known/jwks.json";
            System.out.println("[JWT] Fetching JWKS from: " + jwksUrl);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(jwksUrl))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("[JWT] JWKS response: " + response.body());

            Map<String, Object> jwks = mapper.readValue(response.body(), Map.class);
            List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");

            if (keys == null || keys.isEmpty()) {
                System.out.println("[JWT] JWKS returned empty keys — Supabase may still use HS256");
                return;
            }

            for (Map<String, Object> key : keys) {
                String kty = (String) key.get("kty");
                String alg = (String) key.get("alg");
                System.out.println("[JWT] Found key: kty=" + kty + " alg=" + alg);

                if ("EC".equals(kty) && key.containsKey("x") && key.containsKey("y")) {
                    // ES256 key — reconstruct from x,y coordinates
                    byte[] xBytes = Base64.getUrlDecoder().decode((String) key.get("x"));
                    byte[] yBytes = Base64.getUrlDecoder().decode((String) key.get("y"));

                    ECPoint point = new ECPoint(
                        new BigInteger(1, xBytes),
                        new BigInteger(1, yBytes)
                    );
                    ECParameterSpec spec = ((java.security.interfaces.ECPublicKey)
                        KeyPairGenerator.getInstance("EC").generateKeyPair().getPublic())
                        .getParams();

                    // Use P-256 curve spec
                    AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
                    params.init(new ECGenParameterSpec("secp256r1"));
                    ECParameterSpec ecSpec = params.getParameterSpec(ECParameterSpec.class);

                    ECPublicKeySpec pubKeySpec = new ECPublicKeySpec(point, ecSpec);
                    supabasePublicKey = KeyFactory.getInstance("EC").generatePublic(pubKeySpec);
                    System.out.println("[JWT] ES256 public key loaded from JWKS successfully");
                    return;
                }
            }
            System.out.println("[JWT] No EC key found in JWKS");
        } catch (Exception e) {
            System.err.println("[JWT] Failed to load JWKS: " + e.getMessage());
            e.printStackTrace();
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
            } catch (JwtException ignored) {
                System.out.println("[JWT] ES256 verification failed, trying HS256 fallback");
            }
        }

        // Fall back to custom HS256 JWT
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

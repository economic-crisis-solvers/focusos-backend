package com.focusos.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
public class RealtimeService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeService.class);

    private final WebClient webClient;
    private final String serviceKey;

    public RealtimeService(
        @Value("${supabase.url}") String supabaseUrl,
        @Value("${supabase.service-key}") String serviceKey
    ) {
        this.serviceKey = serviceKey;
        this.webClient = WebClient.builder()
            .baseUrl(supabaseUrl)
            .defaultHeader("apikey", serviceKey)
            .defaultHeader("Authorization", "Bearer " + serviceKey)
            .defaultHeader("Content-Type", "application/json")
            .build();
    }

    public void broadcast(String userId, String event, Map<String, Object> payload) {
        String channel = "focus-" + userId;
        webClient.post()
            .uri("/realtime/v1/api/broadcast")
            .bodyValue(Map.of(
                "messages", List.of(Map.of(
                    "topic",   channel,
                    "event",   event,
                    "payload", payload
                ))
            ))
            .retrieve()
            .toBodilessEntity()
            .subscribe(
                response -> log.debug("[Realtime] Broadcast '{}' to {} OK", event, channel),
                error    -> log.warn("[Realtime] Broadcast failed for user {}: {}", userId, error.getMessage())
            );
    }
}

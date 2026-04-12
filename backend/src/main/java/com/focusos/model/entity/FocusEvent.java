package com.focusos.model.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "focus_events")
public class FocusEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(nullable = false)
    private int score;

    @Column(nullable = false)
    private String state;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> signals;

    // Removed @CreationTimestamp — now we set this manually
    // so the seed script can backdate events properly
    @Column(updatable = false)
    private Instant timestamp;

    @PrePersist
    public void prePersist() {
        // Only set timestamp if not already provided
        if (this.timestamp == null) {
            this.timestamp = Instant.now();
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Map<String, Object> getSignals() { return signals; }
    public void setSignals(Map<String, Object> signals) { this.signals = signals; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}

package com.focusos.model.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_settings")
public class UserSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "focus_threshold")
    private int focusThreshold = 45;

    @Column(name = "whitelist", columnDefinition = "TEXT[]")
    private String[] whitelist = new String[]{};

    @Column(name = "quiet_hours_start")
    private String quietHoursStart = "22:00";

    @Column(name = "quiet_hours_end")
    private String quietHoursEnd = "07:00";

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public int getFocusThreshold() { return focusThreshold; }
    public void setFocusThreshold(int focusThreshold) { this.focusThreshold = focusThreshold; }
    public String[] getWhitelist() { return whitelist; }
    public void setWhitelist(String[] whitelist) { this.whitelist = whitelist; }
    public String getQuietHoursStart() { return quietHoursStart; }
    public void setQuietHoursStart(String quietHoursStart) { this.quietHoursStart = quietHoursStart; }
    public String getQuietHoursEnd() { return quietHoursEnd; }
    public void setQuietHoursEnd(String quietHoursEnd) { this.quietHoursEnd = quietHoursEnd; }
    public Instant getUpdatedAt() { return updatedAt; }
}

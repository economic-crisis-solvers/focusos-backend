package com.focusos.model.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "distraction_events")
public class DistractionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "trigger_category")
    private String triggerCategory;

    @Column(name = "residue_minutes_added")
    private double residueMinutesAdded = 0.0;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public String getTriggerCategory() { return triggerCategory; }
    public void setTriggerCategory(String triggerCategory) { this.triggerCategory = triggerCategory; }
    public double getResidueMinutesAdded() { return residueMinutesAdded; }
    public void setResidueMinutesAdded(double residueMinutesAdded) { this.residueMinutesAdded = residueMinutesAdded; }
}

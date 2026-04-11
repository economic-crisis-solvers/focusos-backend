package com.focusos.repository;

import com.focusos.model.entity.DistractionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface DistractionEventRepository extends JpaRepository<DistractionEvent, UUID> {

    // Fixed: added @Transactional — required for @Modifying queries
    @Modifying
    @Transactional
    @Query("UPDATE DistractionEvent d SET d.endedAt = :now WHERE d.userId = :userId AND d.endedAt IS NULL")
    int closeOpenEvents(@Param("userId") UUID userId, @Param("now") Instant now);

    @Query(value = """
        SELECT
            trigger_category,
            COUNT(*)                                AS event_count,
            COALESCE(SUM(residue_minutes_added), 0) AS total_residue
        FROM distraction_events
        WHERE user_id = :userId
          AND started_at > NOW() - CAST(:days || ' days' AS interval)
        GROUP BY trigger_category
        ORDER BY event_count DESC
        LIMIT 5
        """, nativeQuery = true)
    List<Object[]> getTopDistractions(@Param("userId") UUID userId, @Param("days") int days);

    @Query(value = """
        SELECT
            COUNT(*)                                AS distraction_count,
            COALESCE(SUM(residue_minutes_added), 0) AS total_residue
        FROM distraction_events
        WHERE user_id = :userId
          AND started_at > NOW() - INTERVAL '24 hours'
        """, nativeQuery = true)
    Object[] getResidueStats(@Param("userId") UUID userId);

    List<DistractionEvent> findByUserIdAndEndedAtIsNull(UUID userId);
}

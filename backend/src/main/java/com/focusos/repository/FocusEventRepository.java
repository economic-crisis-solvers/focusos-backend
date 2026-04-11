package com.focusos.repository;

import com.focusos.model.entity.FocusEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FocusEventRepository extends JpaRepository<FocusEvent, UUID> {

    Optional<FocusEvent> findTopByUserIdOrderByTimestampDesc(UUID userId);

    @Query("SELECT COUNT(e) FROM FocusEvent e WHERE e.userId = :userId AND e.timestamp > :since")
    long countByUserIdAndTimestampAfter(@Param("userId") UUID userId, @Param("since") Instant since);

    // Fixed: calculate focused minutes using simple SUM of intervals between consecutive rows
    // Uses a subquery approach to avoid window-function-inside-aggregate error
    @Query(value = """
        SELECT COALESCE(
            EXTRACT(EPOCH FROM (MAX(timestamp) - MIN(timestamp))) / 60,
            0
        )
        FROM focus_events
        WHERE user_id = :userId
          AND state IN ('deep_focus', 'engaged')
          AND timestamp > NOW() - INTERVAL '24 hours'
        """, nativeQuery = true)
    double sumFocusedMinutesToday(@Param("userId") UUID userId);

    @Query(value = """
        SELECT
            date_trunc(:granularity, timestamp)          AS period,
            AVG(score)::int                              AS avg_score,
            mode() WITHIN GROUP (ORDER BY state)         AS state
        FROM focus_events
        WHERE user_id = :userId
          AND timestamp > NOW() - CAST(:days || ' days' AS interval)
        GROUP BY period
        ORDER BY period ASC
        """, nativeQuery = true)
    List<Object[]> getHistory(
        @Param("userId") UUID userId,
        @Param("granularity") String granularity,
        @Param("days") int days
    );

    @Query(value = """
        SELECT
            EXTRACT(HOUR FROM timestamp)::int AS hour,
            AVG(score)::int                   AS avg_score
        FROM focus_events
        WHERE user_id = :userId
          AND timestamp > NOW() - CAST(:days || ' days' AS interval)
        GROUP BY EXTRACT(HOUR FROM timestamp)
        ORDER BY avg_score DESC
        """, nativeQuery = true)
    List<Object[]> getPeakHours(@Param("userId") UUID userId, @Param("days") int days);
}

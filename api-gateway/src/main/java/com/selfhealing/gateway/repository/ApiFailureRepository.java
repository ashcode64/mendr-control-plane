package com.selfhealing.gateway.repository;

import com.selfhealing.gateway.model.ApiFailure;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ApiFailureRepository extends JpaRepository<ApiFailure, UUID> {

    Page<ApiFailure> findAllByOrderByDetectedAtDesc(Pageable pageable);

    List<ApiFailure> findByStatus(ApiFailure.FailureStatus status);

    @Query("SELECT COUNT(f) FROM ApiFailure f WHERE f.detectedAt > :since")
    long countRecentFailures(LocalDateTime since);

    @Query("SELECT f.serviceA, f.serviceB, COUNT(f) as cnt FROM ApiFailure f GROUP BY f.serviceA, f.serviceB ORDER BY cnt DESC")
    List<Object[]> getFailuresByServicePair();
}

package com.securebank.ledger;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRepository extends JpaRepository<ReconciliationReport, Long> {
    Page<ReconciliationReport> findAllByOrderByIdDesc(Pageable pageable);
}

package com.securebank.beneficiary;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, Long> {
    List<Beneficiary> findByCustomerIdAndActiveTrueOrderByIdAsc(Long customerId);

    Optional<Beneficiary> findByCustomerIdAndAccountNumber(Long customerId, String accountNumber);

    Optional<Beneficiary> findByIdAndCustomerId(Long id, Long customerId);

    boolean existsByCustomerIdAndAccountNumberAndActiveTrue(Long customerId, String accountNumber);
}

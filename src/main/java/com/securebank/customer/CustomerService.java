package com.securebank.customer;

import com.securebank.audit.AuditService;
import com.securebank.common.BusinessException;
import com.securebank.common.ErrorCode;
import com.securebank.customer.CustomerDtos.CustomerResponse;
import com.securebank.customer.CustomerDtos.KycRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository repository;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public CustomerResponse getProfile(Long customerId) {
        return CustomerResponse.from(find(customerId));
    }

    @Transactional
    public CustomerResponse submitKyc(Long customerId, KycRequest request) {
        Customer c = find(customerId);
        if (c.getKycStatus() == KycStatus.VERIFIED) {
            throw new BusinessException(ErrorCode.DUPLICATE, "KYC is already verified");
        }
        if (c.getKycStatus() == KycStatus.PENDING) {
            throw new BusinessException(ErrorCode.DUPLICATE, "KYC is already under review");
        }
        c.setPanNumber(request.panNumber());
        c.setAadhaarNumber(request.aadhaarNumber());
        c.setAddress(request.address());
        c.setKycStatus(KycStatus.PENDING);
        c.setKycRejectionReason(null);
        repository.save(c);
        audit.record("customer:" + customerId, "KYC_SUBMITTED", "Customer", String.valueOf(customerId), "SUCCESS", null);
        return CustomerResponse.from(c);
    }

    @Transactional(readOnly = true)
    public Page<CustomerResponse> pendingKyc(Pageable pageable) {
        return repository.findByKycStatus(KycStatus.PENDING, pageable).map(CustomerResponse::from);
    }

    @Transactional
    public CustomerResponse reviewKyc(Long customerId, boolean approve, String reason, Long adminId) {
        Customer c = find(customerId);
        if (c.getKycStatus() != KycStatus.PENDING) {
            throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED, "No pending KYC for this customer");
        }
        c.setKycStatus(approve ? KycStatus.VERIFIED : KycStatus.REJECTED);
        c.setKycRejectionReason(approve ? null : reason);
        repository.save(c);
        audit.record("admin:" + adminId, approve ? "KYC_APPROVED" : "KYC_REJECTED", "Customer",
                String.valueOf(customerId), "SUCCESS", reason);
        return CustomerResponse.from(c);
    }

    @Transactional
    public void unlock(Long customerId, Long adminId) {
        Customer c = find(customerId);
        c.setFailedLoginAttempts(0);
        c.setLockedUntil(null);
        repository.save(c);
        audit.record("admin:" + adminId, "CUSTOMER_UNLOCKED", "Customer", String.valueOf(customerId), "SUCCESS", null);
    }

    private Customer find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Customer not found"));
    }
}

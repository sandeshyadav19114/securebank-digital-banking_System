package com.securebank.beneficiary;

import com.securebank.account.Account;
import com.securebank.account.AccountRepository;
import com.securebank.account.AccountStatus;
import com.securebank.audit.AuditService;
import com.securebank.beneficiary.BeneficiaryDtos.AddBeneficiaryRequest;
import com.securebank.beneficiary.BeneficiaryDtos.BeneficiaryResponse;
import com.securebank.common.BusinessException;
import com.securebank.common.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BeneficiaryService {

    private final BeneficiaryRepository repository;
    private final AccountRepository accounts;
    private final AuditService audit;

    @Transactional
    public BeneficiaryResponse add(Long customerId, AddBeneficiaryRequest r) {
        Account target = accounts.findByAccountNumber(r.accountNumber())
                .filter(a -> !a.isSystemAccount() && a.getStatus() == AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Beneficiary account not found or inactive"));
        Beneficiary b = repository.findByCustomerIdAndAccountNumber(customerId, target.getAccountNumber())
                .orElseGet(Beneficiary::new);
        if (b.getId() != null && b.isActive()) {
            throw new BusinessException(ErrorCode.DUPLICATE, "Beneficiary already exists");
        }
        b.setCustomerId(customerId);
        b.setAccountNumber(target.getAccountNumber());
        b.setName(r.name().trim());
        b.setNickname(r.nickname());
        b.setActive(true);
        repository.save(b);
        audit.record("customer:" + customerId, "BENEFICIARY_ADDED", "Beneficiary", target.getAccountNumber(), "SUCCESS", null);
        return BeneficiaryResponse.from(b);
    }

    @Transactional(readOnly = true)
    public List<BeneficiaryResponse> list(Long customerId) {
        return repository.findByCustomerIdAndActiveTrueOrderByIdAsc(customerId).stream()
                .map(BeneficiaryResponse::from).toList();
    }

    @Transactional
    public void remove(Long customerId, Long beneficiaryId) {
        Beneficiary b = repository.findByIdAndCustomerId(beneficiaryId, customerId)
                .filter(Beneficiary::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Beneficiary not found"));
        b.setActive(false);
        repository.save(b);
        audit.record("customer:" + customerId, "BENEFICIARY_REMOVED", "Beneficiary", b.getAccountNumber(), "SUCCESS", null);
    }
}

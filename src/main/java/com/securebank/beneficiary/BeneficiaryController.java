package com.securebank.beneficiary;

import com.securebank.beneficiary.BeneficiaryDtos.AddBeneficiaryRequest;
import com.securebank.beneficiary.BeneficiaryDtos.BeneficiaryResponse;
import com.securebank.security.SecurityUtils;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/beneficiaries")
@RequiredArgsConstructor
@Tag(name = "Beneficiaries")
@SecurityRequirement(name = "bearerAuth")
public class BeneficiaryController {

    private final BeneficiaryService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BeneficiaryResponse add(@Valid @RequestBody AddBeneficiaryRequest request) {
        return service.add(SecurityUtils.currentCustomerId(), request);
    }

    @GetMapping
    public List<BeneficiaryResponse> list() {
        return service.list(SecurityUtils.currentCustomerId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long id) {
        service.remove(SecurityUtils.currentCustomerId(), id);
    }
}

package com.securebank.customer;

import com.securebank.customer.CustomerDtos.CustomerResponse;
import com.securebank.customer.CustomerDtos.KycRequest;
import com.securebank.security.SecurityUtils;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customers/me")
@RequiredArgsConstructor
@Tag(name = "Customer & KYC")
@SecurityRequirement(name = "bearerAuth")
public class CustomerController {

    private final CustomerService service;

    @GetMapping
    public CustomerResponse me() {
        return service.getProfile(SecurityUtils.currentCustomerId());
    }

    @PostMapping("/kyc")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CustomerResponse submitKyc(@Valid @RequestBody KycRequest request) {
        return service.submitKyc(SecurityUtils.currentCustomerId(), request);
    }

    @GetMapping("/kyc")
    public CustomerResponse kycStatus() {
        return service.getProfile(SecurityUtils.currentCustomerId());
    }
}

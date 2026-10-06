package com.securebank.config;

import com.securebank.account.Account;
import com.securebank.account.AccountRepository;
import com.securebank.account.AccountStatus;
import com.securebank.account.AccountType;
import com.securebank.customer.Customer;
import com.securebank.customer.CustomerRepository;
import com.securebank.customer.KycStatus;
import com.securebank.customer.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the bank's cash general-ledger account (needed for double entry) and an optional bootstrap admin. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final AccountRepository accounts;
    private final CustomerRepository customers;
    private final PasswordEncoder encoder;
    private final BankProperties bank;
    private final BootstrapProperties bootstrap;

    @Override
    public void run(ApplicationArguments args) {
        if (!accounts.existsByAccountNumber(bank.glAccountNumber())) {
            Account gl = new Account();
            gl.setAccountNumber(bank.glAccountNumber());
            gl.setAccountType(AccountType.CURRENT);
            gl.setSystemAccount(true);
            gl.setStatus(AccountStatus.ACTIVE);
            try {
                accounts.saveAndFlush(gl);
                log.info("Created cash GL account {}", bank.glAccountNumber());
            } catch (DataIntegrityViolationException race) {
                log.info("GL account created concurrently by another instance");
            }
        }
        String email = bootstrap.adminEmail();
        if (email != null && !email.isBlank() && bootstrap.adminPassword() != null
                && !customers.existsByEmailIgnoreCase(email)) {
            Customer admin = new Customer();
            admin.setFullName("Bank Administrator");
            admin.setEmail(email.toLowerCase());
            admin.setPhone("0000000000");
            admin.setPasswordHash(encoder.encode(bootstrap.adminPassword()));
            admin.setRole(Role.ADMIN);
            admin.setKycStatus(KycStatus.VERIFIED);
            try {
                customers.saveAndFlush(admin);
                log.info("Created bootstrap admin {}", email);
            } catch (DataIntegrityViolationException race) {
                log.info("Bootstrap admin created concurrently by another instance");
            }
        }
    }
}

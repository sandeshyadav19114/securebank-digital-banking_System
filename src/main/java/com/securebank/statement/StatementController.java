package com.securebank.statement;

import com.securebank.security.SecurityUtils;
import com.securebank.statement.StatementDtos.StatementResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/accounts/{accountNumber}/statement")
@RequiredArgsConstructor
@Tag(name = "Statements")
@SecurityRequirement(name = "bearerAuth")
public class StatementController {

    private final StatementService service;

    @GetMapping
    public StatementResponse statement(@PathVariable String accountNumber,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.generate(accountNumber, SecurityUtils.currentCustomerId(), from, to);
    }

    @GetMapping("/csv")
    public ResponseEntity<byte[]> csv(@PathVariable String accountNumber,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        StatementResponse s = service.generate(accountNumber, SecurityUtils.currentCustomerId(), from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"statement-" + accountNumber + "-" + from + "_" + to + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(service.toCsv(s).getBytes(StandardCharsets.UTF_8));
    }
}

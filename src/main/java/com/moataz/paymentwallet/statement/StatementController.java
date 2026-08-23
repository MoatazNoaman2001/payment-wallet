package com.moataz.paymentwallet.statement;

import com.moataz.paymentwallet.transfer.LedgerDirection;
import com.moataz.paymentwallet.transfer.TransferType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api/accounts/{accountNumber}")
@Tag(name = "Statements", description = "Paginated statement and spend analytics")
public class StatementController {
    private final StatementService statementService;

    public StatementController(StatementService statementService) {
        this.statementService = statementService;
    }

    @Operation(summary = "Account statement",
               description = "Paginated ledger lines with optional filters. Runs a fixed "
                           + "number of queries regardless of page size.")
    @PreAuthorize("@ownership.canServiceAccount(#accountNumber, authentication)")
    @GetMapping("/statement")
    public Page<StatementLine> statement(
            @PathVariable String accountNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) TransferType type,
            @RequestParam(required = false) LedgerDirection direction,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        StatementFilter filter = new StatementFilter(from, to, type, direction, minAmount, maxAmount);
        return statementService.statement(accountNumber, filter, pageable);
    }

    @Operation(summary = "Spend by tag for one month",
               description = "Group-by aggregate over outgoing POSTED transfers, e.g. month=2026-08")
    @PreAuthorize("@ownership.canServiceAccount(#accountNumber, authentication)")
    @GetMapping("/spend-by-tag")
    public List<TagSpendRow> spendByTag(
            @PathVariable String accountNumber,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return statementService.spendByTag(accountNumber, month);
    }
}

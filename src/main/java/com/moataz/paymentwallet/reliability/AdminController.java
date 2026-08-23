package com.moataz.paymentwallet.reliability;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin", description = "Reconciliation and outbox operations")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final ReconciliationService reconciliationService;
    private final OutboxPublisher outboxPublisher;

    @Operation(summary = "Reconcile balances against the ledger",
               description = "Returns every account whose cached balance disagrees with the sum "
                           + "of its ledger entries. An empty list is the healthy answer.")
    @GetMapping("/reconciliation")
    public List<Map<String, Object>> reconcile() {
        return reconciliationService.findDrift().stream()
                .map(d -> Map.<String, Object>of(
                        "accountNumber", d.getAccountNumber(),
                        "balance", d.getBalance(),
                        "ledgerBalance", d.getLedgerBalance(),
                        "difference", d.getBalance().subtract(d.getLedgerBalance())))
                .toList();
    }

    @Operation(summary = "Publish pending outbox events",
               description = "Normally runs on a timer; exposed so the effect is visible on demand.")
    @PostMapping("/outbox/publish")
    public Map<String, Integer> publish() {
        return Map.of("published", outboxPublisher.publishBatch(100));
    }
}

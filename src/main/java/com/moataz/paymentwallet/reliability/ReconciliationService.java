package com.moataz.paymentwallet.reliability;

import com.moataz.paymentwallet.account.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public List<BalanceDriftView> findDrift() {
        return accountRepository.findBalanceDrift();
    }

    @Transactional(readOnly = true)
    public int report() {
        List<BalanceDriftView> drift = findDrift();
        if (drift.isEmpty()) {
            log.info("Reconciliation: all balances agree with the ledger");
            return 0;
        }
        log.error("Reconciliation: {} account(s) drifted from the ledger", drift.size());
        drift.forEach(d -> log.error("  {} balance={} ledger={} difference={}",
                d.getAccountNumber(), d.getBalance(), d.getLedgerBalance(),
                d.getBalance().subtract(d.getLedgerBalance())));
        return drift.size();
    }
}

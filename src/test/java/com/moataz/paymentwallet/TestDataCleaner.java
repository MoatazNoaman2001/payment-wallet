package com.moataz.paymentwallet;

import com.moataz.paymentwallet.account.Account;
import com.moataz.paymentwallet.account.AccountRepository;
import com.moataz.paymentwallet.transfer.LedgerEntryRepository;
import com.moataz.paymentwallet.transfer.OutboxEventRepository;
import com.moataz.paymentwallet.transfer.Transfer;
import com.moataz.paymentwallet.transfer.TransferRepository;
import com.moataz.paymentwallet.user.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Test-scope only. Removes exactly what a test created and nothing else.
 *
 * The suite shares a database with local development, so deleteAll() on transfer,
 * ledger_entry or outbox_event destroys data created by hand through Swagger — and
 * leaves account.balance pointing at a history that no longer exists.
 *
 * Counterparties the test did not create (the SYSTEM settlement accounts) survive, so
 * their balance is recomputed from what remains in the ledger. The ledger is the source
 * of truth; rebuilding the projection from it is the same thing the Phase 5
 * reconciliation job will do.
 */
@Component
@RequiredArgsConstructor
class TestDataCleaner {

    private final TransferRepository transferRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final AccountRepository accountRepository;
    private final AppUserRepository userRepository;

    @Transactional
    void deleteCreated(List<Long> accountIds, List<Long> userIds) {
        if (!accountIds.isEmpty()) {
            List<Transfer> mine =
                    transferRepository.findBySourceAccountIdInOrDestAccountIdIn(accountIds, accountIds);

            Set<Long> counterparties = new HashSet<>();
            List<Long> transferIds = mine.stream().map(Transfer::getId).toList();
            List<String> references = mine.stream().map(Transfer::getReference).toList();
            mine.forEach(t -> {
                counterparties.add(t.getSourceAccount().getId());
                counterparties.add(t.getDestAccount().getId());
            });
            counterparties.removeAll(accountIds);

            if (!references.isEmpty()) {
                outboxEventRepository.deleteByAggregateIdIn(references);
            }
            if (!transferIds.isEmpty()) {
                ledgerEntryRepository.deleteByTransferIdIn(transferIds);
            }
            // a REVERSAL has a foreign key to the transfer it compensates, so it has to
            // go first or Postgres rejects the delete
            List<Transfer> reversals = mine.stream().filter(t -> t.getReversesTransfer() != null).toList();
            List<Transfer> rest = mine.stream().filter(t -> t.getReversesTransfer() == null).toList();
            transferRepository.deleteAll(reversals);
            transferRepository.flush();
            transferRepository.deleteAll(rest);
            accountRepository.deleteAllById(accountIds);

            counterparties.forEach(this::rebuildBalanceFromLedger);
        }
        if (!userIds.isEmpty()) {
            userRepository.deleteAllById(userIds);
        }
    }

    private void rebuildBalanceFromLedger(Long accountId) {
        accountRepository.findById(accountId).ifPresent(account -> {
            BigDecimal fromLedger = ledgerEntryRepository.balanceFromLedger(accountId);
            account.setBalance(fromLedger.setScale(4));
        });
    }
}

package com.moataz.paymentwallet.statement;

import com.moataz.paymentwallet.account.AccountRepository;
import com.moataz.paymentwallet.common.error.NotFoundException;
import com.moataz.paymentwallet.transfer.TransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StatementService {

    private final com.moataz.paymentwallet.transfer.LedgerEntryRepository ledgerEntryRepository;
    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public Page<StatementLine> statement(String accountNumber, StatementFilter filter, Pageable pageable) {
        requireAccount(accountNumber);
        return ledgerEntryRepository
                .findAll(LedgerEntrySpecifications.matching(accountNumber, filter), pageable)
                .map(StatementLine::from);
    }

    @Transactional(readOnly = true)
    public List<TagSpendRow> spendByTag(String accountNumber, YearMonth month) {
        requireAccount(accountNumber);
        OffsetDateTime from = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        return transferRepository.spendByTag(accountNumber, from, to);
    }

    private void requireAccount(String accountNumber) {
        if (accountRepository.findIdByAccountNumber(accountNumber).isEmpty()) {
            throw new NotFoundException("No account " + accountNumber);
        }
    }
}

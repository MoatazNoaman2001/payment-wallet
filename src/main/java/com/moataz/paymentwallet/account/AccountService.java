package com.moataz.paymentwallet.account;

import com.moataz.paymentwallet.account.dto.AccountResponse;
import com.moataz.paymentwallet.account.dto.AccountRow;
import com.moataz.paymentwallet.account.dto.OwnerAccounts;
import com.moataz.paymentwallet.account.dto.OpenAccountRequest;
import com.moataz.paymentwallet.common.error.BusinessRuleException;
import com.moataz.paymentwallet.common.error.NotFoundException;
import com.moataz.paymentwallet.user.AppUser;
import com.moataz.paymentwallet.user.AppUserRepository;
import com.moataz.paymentwallet.user.UserStatus;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {
    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository accountRepository;
    private final AppUserRepository userRepository;
    private final CurrencyRepository currencyRepository;

    @Transactional
    public AccountResponse open(OpenAccountRequest request, UUID ownerPublicId, UUID actorPublicId) {
        AppUser owner = userRepository.findByPublicId(ownerPublicId)
                .orElseThrow(() -> new NotFoundException("No user with id " + ownerPublicId));

        if (request.type() == AccountType.SYSTEM) {
            throw new BusinessRuleException(
                    "SYSTEM settlement accounts cannot be opened through the API: they may hold a "
                    + "negative balance, so they are created by migration alongside a currency");
        }
        if (owner.getStatus() == UserStatus.SUSPENDED || owner.getStatus() == UserStatus.CLOSED) {
            throw new BusinessRuleException("Cannot open an account for a " + owner.getStatus() + " user");
        }

        Currency currency = currencyRepository.findById(request.currencyCode())
                .orElseThrow(() -> new NotFoundException(
                        "Unknown currency: " + request.currencyCode()));

        Account account = new Account();
        account.setAccountNumber(generateAccountNumber());
        account.setUser(owner);
        account.setOpenedBy(actorPublicId.equals(ownerPublicId)
                ? owner
                : userRepository.findByPublicId(actorPublicId).orElse(owner));
        account.setCurrency(currency);
        account.setType(request.type());
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(BigDecimal.ZERO);
        account.setDailyLimit(request.dailyLimit());

        return AccountResponse.from(accountRepository.save(account));
    }

    @Transactional(readOnly = true)
    public AccountResponse findByNumber(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new NotFoundException("No account " + accountNumber));
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public Page<AccountResponse> list(UUID ownerPublicId, Pageable pageable) {
        if (ownerPublicId == null) {
            return accountRepository.findPageWithOwner(pageable).map(AccountResponse::from);
        }
        if (userRepository.findByPublicId(ownerPublicId).isEmpty()) {
            throw new NotFoundException("No user with id " + ownerPublicId);
        }
        return accountRepository.findPageByOwner(ownerPublicId, pageable).map(AccountResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<AccountRow> listRows(UUID ownerPublicId, Pageable pageable) {
        Page<Account> accounts = ownerPublicId == null
                ? accountRepository.findPageWithOwner(pageable)
                : accountRepository.findPageByOwner(ownerPublicId, pageable);
        return accounts.map(AccountRow::from);
    }

    @Transactional(readOnly = true)
    public Page<OwnerAccounts> listByOwner(Pageable pageable) {
        Page<AppUser> owners = accountRepository.findCustomersWithAccounts(pageable);
        if (owners.isEmpty()) {
            return owners.map(u -> new OwnerAccounts(u.getPublicId(), u.getFullName(),
                    u.getEmail(), u.getStatus().name(), List.of()));
        }

        Map<Long, List<AccountRow>> byOwner = accountRepository
                .findCustomerAccountsFor(owners.getContent().stream().map(AppUser::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(a -> a.getUser().getId(),
                        LinkedHashMap::new,
                        Collectors.mapping(AccountRow::from, Collectors.toList())));

        return owners.map(u -> new OwnerAccounts(u.getPublicId(), u.getFullName(), u.getEmail(),
                u.getStatus().name(), byOwner.getOrDefault(u.getId(), List.of())));
    }

    @Transactional
    public AccountResponse freeze(String accountNumber, String reason, UUID actorPublicId) {
        return changeStatus(accountNumber, AccountStatus.FROZEN, reason, actorPublicId);
    }

    @Transactional
    public AccountResponse unfreeze(String accountNumber, String reason, UUID actorPublicId) {
        return changeStatus(accountNumber, AccountStatus.ACTIVE, reason, actorPublicId);
    }

    private AccountResponse changeStatus(String accountNumber, AccountStatus target,
                                         String reason, UUID actorPublicId) {
        Account account = accountRepository.findWithOwnerByAccountNumber(accountNumber)
                .orElseThrow(() -> new NotFoundException("No account " + accountNumber));

        if (account.getType() == AccountType.SYSTEM) {
            throw new BusinessRuleException(
                    "A settlement account cannot be frozen: every deposit and withdrawal in that "
                    + "currency would stop");
        }
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new BusinessRuleException("A closed account cannot change status");
        }
        if (account.getStatus() == target) {
            throw new BusinessRuleException("Account is already " + target);
        }

        account.setStatus(target);
        account.setStatusReason(reason);
        account.setStatusChangedAt(OffsetDateTime.now(ZoneOffset.UTC));
        account.setStatusChangedBy(userRepository.findByPublicId(actorPublicId).orElse(null));

        log.warn("Account {} set to {} by {} - {}", accountNumber, target, actorPublicId, reason);
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public AccountRow findRowByNumber(String accountNumber) {
        return accountRepository.findWithOwnerByAccountNumber(accountNumber)
                .map(AccountRow::from)
                .orElseThrow(() -> new NotFoundException("No account " + accountNumber));
    }

    @Transactional(readOnly = true)
    public List<AccountRow> settlementAccounts() {
        return accountRepository.findSettlementAccounts().stream().map(AccountRow::from).toList();
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> findAllForOwner(UUID ownerPublicId) {
        if (!userRepository.findByPublicId(ownerPublicId).isPresent()) {
            throw new NotFoundException("No user with id " + ownerPublicId);
        }
        return accountRepository.findAllByOwner(ownerPublicId).stream()
                .map(AccountResponse::from)
                .toList();
    }

    private String generateAccountNumber() {
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder sb = new StringBuilder("PW");
            for (int i = 0; i < 16; i++) {
                sb.append(RANDOM.nextInt(10));
            }
            String candidate = sb.toString();
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique account number");
    }
}

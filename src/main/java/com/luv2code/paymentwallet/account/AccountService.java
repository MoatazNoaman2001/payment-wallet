package com.luv2code.paymentwallet.account;

import com.luv2code.paymentwallet.account.dto.AccountResponse;
import com.luv2code.paymentwallet.account.dto.OpenAccountRequest;
import com.luv2code.paymentwallet.common.error.NotFoundException;
import com.luv2code.paymentwallet.user.AppUser;
import com.luv2code.paymentwallet.user.AppUserRepository;
import com.luv2code.paymentwallet.user.UserStatus;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository accountRepository;
    private final AppUserRepository userRepository;
    private final CurrencyRepository currencyRepository;

//    public AccountService(AccountRepository accountRepository,
//                          AppUserRepository userRepository,
//                          CurrencyRepository currencyRepository) {
//        this.accountRepository = accountRepository;
//        this.userRepository = userRepository;
//        this.currencyRepository = currencyRepository;
//    }

    @Transactional
    public AccountResponse open(OpenAccountRequest request) {
        AppUser owner = userRepository.findByPublicId(request.ownerPublicId())
                .orElseThrow(() -> new NotFoundException(
                        "No user with id " + request.ownerPublicId()));

        if (owner.getStatus() == UserStatus.SUSPENDED || owner.getStatus() == UserStatus.CLOSED) {
            throw new IllegalStateException("Cannot open an account for a " + owner.getStatus() + " user");
        }

        Currency currency = currencyRepository.findById(request.currencyCode())
                .orElseThrow(() -> new NotFoundException(
                        "Unknown currency: " + request.currencyCode()));

        Account account = new Account();
        account.setAccountNumber(generateAccountNumber());
        account.setUser(owner);
        account.setCurrency(currency);
        account.setType(request.type());
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(BigDecimal.ZERO);      // money only ever enters through the ledger
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
    public List<AccountResponse> findAllForOwner(UUID ownerPublicId) {
        if (!userRepository.findByPublicId(ownerPublicId).isPresent()) {
            throw new NotFoundException("No user with id " + ownerPublicId);
        }
        // findAllByOwner uses join fetch, so this is ONE query no matter how many accounts.
        return accountRepository.findAllByOwner(ownerPublicId).stream()
                .map(AccountResponse::from)
                .toList();
    }

    /** PW + 16 digits, comfortably inside VARCHAR(24). Retries on the astronomically rare clash. */
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

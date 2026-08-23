package com.moataz.paymentwallet.config;

import com.moataz.paymentwallet.account.Account;
import com.moataz.paymentwallet.account.AccountRepository;
import com.moataz.paymentwallet.account.AccountStatus;
import com.moataz.paymentwallet.account.AccountType;
import com.moataz.paymentwallet.account.Currency;
import com.moataz.paymentwallet.account.CurrencyRepository;
import com.moataz.paymentwallet.user.AppUser;
import com.moataz.paymentwallet.user.AppUserRepository;
import com.moataz.paymentwallet.user.UserStatus;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Component
@Order(0)
@RequiredArgsConstructor
public class SettlementAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SettlementAccountInitializer.class);
    private static final String SYSTEM_EMAIL = "system@paymentwallet.local";

    private final CurrencyRepository currencyRepository;
    private final AccountRepository accountRepository;
    private final AppUserRepository userRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppUser systemUser = userRepository.findByEmailWithRoles(SYSTEM_EMAIL)
                .orElseGet(this::createSystemUser);

        List<String> created = currencyRepository.findAll().stream()
                .filter(c -> accountRepository.findSystemAccountNumber(c.getCode()).isEmpty())
                .map(c -> create(c, systemUser))
                .toList();

        if (!created.isEmpty()) {
            log.warn("Created missing settlement accounts: {}", created);
        }
    }

    private AppUser createSystemUser() {
        AppUser user = new AppUser();
        user.setEmail(SYSTEM_EMAIL);
        user.setPhone("+000000000000");
        user.setPasswordHash("N/A");
        user.setFullName("System");
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.saveAndFlush(user);
    }

    private String create(Currency currency, AppUser systemUser) {
        Account account = new Account();
        account.setAccountNumber("SYSTEM-" + currency.getCode());
        account.setUser(systemUser);
        account.setCurrency(currency);
        account.setType(AccountType.SYSTEM);
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(BigDecimal.ZERO);
        accountRepository.saveAndFlush(account);
        return account.getAccountNumber();
    }
}

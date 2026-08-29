package com.moataz.paymentwallet.config;

import com.moataz.paymentwallet.account.Account;
import com.moataz.paymentwallet.account.AccountRepository;
import com.moataz.paymentwallet.account.AccountStatus;
import com.moataz.paymentwallet.account.AccountType;
import com.moataz.paymentwallet.account.Currency;
import com.moataz.paymentwallet.account.CurrencyRepository;
import com.moataz.paymentwallet.funding.provider.PaymentProviderAdapter;
import com.moataz.paymentwallet.user.AppUser;
import com.moataz.paymentwallet.user.AppUserRepository;
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

/**
 * Every payment provider gets its own clearing account per currency.
 *
 * Money that Stripe has collected is not money in the drawer: the provider holds it for days
 * before paying out, and the wallet owes the customer immediately. Keeping one settlement
 * account per provider is what lets you ask "what does Stripe owe us today?" and compare that
 * to their statement. One shared SYSTEM account would answer only "what does the outside world
 * owe us in total", which reconciles against nothing.
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class ClearingAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ClearingAccountInitializer.class);
    private static final String SYSTEM_EMAIL = "system@paymentwallet.local";

    private final CurrencyRepository currencyRepository;
    private final AccountRepository accountRepository;
    private final AppUserRepository userRepository;
    private final List<PaymentProviderAdapter> adapters;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppUser systemUser = userRepository.findByEmailWithRoles(SYSTEM_EMAIL)
                .orElseThrow(() -> new IllegalStateException(
                        "System user missing: SettlementAccountInitializer must run first"));

        List<String> created = adapters.stream()
                .map(adapter -> adapter.provider().name())
                .flatMap(provider -> currencyRepository.findAll().stream()
                        .filter(c -> accountRepository
                                .findClearingAccountNumber(provider, c.getCode()).isEmpty())
                        .map(c -> create(provider, c, systemUser)))
                .toList();

        if (!created.isEmpty()) {
            log.warn("Created missing provider clearing accounts: {}", created);
        }
    }

    private String create(String provider, Currency currency, AppUser systemUser) {
        Account account = new Account();
        account.setAccountNumber("CLEARING-" + provider + "-" + currency.getCode());
        account.setUser(systemUser);
        account.setCurrency(currency);
        account.setType(AccountType.SYSTEM);
        account.setProvider(provider);
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(BigDecimal.ZERO);
        accountRepository.saveAndFlush(account);
        return account.getAccountNumber();
    }
}

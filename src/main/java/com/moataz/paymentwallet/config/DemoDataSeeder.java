package com.moataz.paymentwallet.config;

import com.moataz.paymentwallet.account.AccountService;
import com.moataz.paymentwallet.account.AccountType;
import com.moataz.paymentwallet.account.dto.OpenAccountRequest;
import com.moataz.paymentwallet.transfer.CashService;
import com.moataz.paymentwallet.transfer.TransferService;
import com.moataz.paymentwallet.transfer.TransferType;
import com.moataz.paymentwallet.transfer.dto.CashRequest;
import com.moataz.paymentwallet.transfer.dto.TransferRequest;
import com.moataz.paymentwallet.transfer.dto.TransferResponse;
import com.moataz.paymentwallet.user.AppUserRepository;
import com.moataz.paymentwallet.user.UserService;
import com.moataz.paymentwallet.user.dto.RegisterUserRequest;
import com.moataz.paymentwallet.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "demo.seed", havingValue = "true")
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String PASSWORD = "demo-pass-12345";

    private final UserService userService;
    private final AccountService accountService;
    private final CashService cashService;
    private final TransferService transferService;
    private final AppUserRepository userRepository;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByEmail("mona@demo.local")) {
            log.info("Demo data already present, skipping seed");
            return;
        }

        UserResponse mona = register("mona@demo.local", "+201000000001", "Mona Ali");
        UserResponse ahmed = register("ahmed@demo.local", "+201000000002", "Ahmed Hassan");

        String monaWallet = openWallet(mona.publicId());
        String ahmedWallet = openWallet(ahmed.publicId());

        UUID staff = userRepository.findByEmailWithRoles("teller@paymentwallet.local")
                .map(u -> u.getPublicId()).orElse(mona.publicId());

        cashService.deposit(monaWallet, staff,
                new CashRequest(new BigDecimal("5000.0000"), "Salary payout"), key());
        cashService.deposit(ahmedWallet, staff,
                new CashRequest(new BigDecimal("1200.0000"), "Card top-up"), key());

        pay(mona.publicId(), monaWallet, ahmedWallet, "450.0000", "Weekly groceries", "groceries");
        pay(mona.publicId(), monaWallet, ahmedWallet, "1800.0000", "August rent", "rent");
        pay(mona.publicId(), monaWallet, ahmedWallet, "75.5000", "Taxi to airport", "transport");
        pay(mona.publicId(), monaWallet, ahmedWallet, "320.0000", "Supermarket", "groceries");
        pay(ahmed.publicId(), ahmedWallet, monaWallet, "200.0000", "Splitting dinner", "entertainment");

        cashService.withdraw(ahmedWallet, staff,
                new CashRequest(new BigDecimal("500.0000"), "ATM withdrawal"), key());

        log.info("""

                ================ demo data ready ================
                  mona@demo.local  / {}   wallet {}
                  ahmed@demo.local / {}   wallet {}
                  admin@paymentwallet.local / admin12345
                =================================================
                """, PASSWORD, monaWallet, PASSWORD, ahmedWallet);
    }

    private UserResponse register(String email, String phone, String fullName) {
        UserResponse user = userService.register(new RegisterUserRequest(email, phone, PASSWORD, fullName));
        return userService.activate(user.publicId());
    }

    private String openWallet(UUID owner) {
        return accountService.open(
                new OpenAccountRequest(owner, "EGP", AccountType.WALLET, new BigDecimal("10000.0000")),
                owner, owner).accountNumber();
    }

    private void pay(UUID actor, String from, String to, String amount, String description, String tag) {
        TransferResponse response = transferService.execute(
                new TransferRequest(from, to, new BigDecimal(amount), "EGP", TransferType.P2P, description),
                key(), actor);
        transferService.replaceTags(response.reference(), Set.of(tag));
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}

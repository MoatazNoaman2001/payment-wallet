package com.moataz.paymentwallet;

import com.moataz.paymentwallet.account.AccountRepository;
import com.moataz.paymentwallet.account.AccountService;
import com.moataz.paymentwallet.account.AccountType;
import com.moataz.paymentwallet.account.dto.OpenAccountRequest;
import com.moataz.paymentwallet.funding.FundingService;
import com.moataz.paymentwallet.funding.PaymentProvider;
import com.moataz.paymentwallet.funding.dto.FundingRequest;
import com.moataz.paymentwallet.funding.dto.PaymentIntentResponse;
import com.moataz.paymentwallet.user.AppUserRepository;
import com.moataz.paymentwallet.user.KycService;
import com.moataz.paymentwallet.user.KycTier;
import com.moataz.paymentwallet.user.UserService;
import com.moataz.paymentwallet.user.dto.KycSubmissionRequest;
import com.moataz.paymentwallet.user.dto.RegisterUserRequest;
import com.moataz.paymentwallet.user.dto.UserResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deliberately <em>not</em> {@code @Transactional}.
 *
 * The rest of the funding tests run inside a test transaction, which keeps a Hibernate session
 * open for their whole duration and quietly makes every lazy association work. That hid a real
 * bug: the idempotent-retry path built its response by calling a {@code @Transactional} method
 * on its own class, so Spring's proxy was bypassed, no transaction was started, and reading
 * the account off the detached intent threw LazyInitializationException. It only failed against
 * a running server.
 *
 * A test with no ambient transaction is the only kind that can catch that.
 */
@SpringBootTest
class FundingIdempotencyTest {

    @Autowired FundingService fundingService;
    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired UserService userService;
    @Autowired KycService kycService;
    @Autowired AppUserRepository userRepository;
    @Autowired TestDataCleaner testDataCleaner;

    private final List<Long> createdAccountIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        testDataCleaner.deleteCreated(createdAccountIds, createdUserIds);
        createdAccountIds.clear();
        createdUserIds.clear();
    }

    @Test
    @DisplayName("retrying with the same key returns the whole payment, not just its reference")
    void replayReturnsAFullyReadableResponse() {
        UserResponse customer = verifiedCustomer();
        String wallet = openWallet(customer);

        String key = UUID.randomUUID().toString();
        FundingRequest request = new FundingRequest(wallet, PaymentProvider.SANDBOX,
                new BigDecimal("25.0000"), null);

        PaymentIntentResponse first = fundingService.deposit(request, customer.publicId(), key);
        PaymentIntentResponse replay = fundingService.deposit(request, customer.publicId(), key);

        assertThat(replay.reference()).isEqualTo(first.reference());

        // every one of these reads an association that is lazy on the entity
        assertThat(replay.accountNumber()).isEqualTo(wallet);
        assertThat(replay.currencyCode()).isEqualTo("EGP");
        assertThat(replay.providerReference()).isEqualTo(first.providerReference());
        assertThat(replay.amount()).isEqualByComparingTo("25.0000");
    }

    private UserResponse verifiedCustomer() {
        UserResponse user = userService.register(new RegisterUserRequest(
                "idem-" + UUID.randomUUID() + "@example.com",
                "+2" + (System.nanoTime() % 1000000000000L),
                "supersecret1", "Idempotency Probe"));
        kycService.submit(user.publicId(), new KycSubmissionRequest(
                String.valueOf(System.nanoTime() % 100000000000000L),
                LocalDate.of(1990, 1, 1), "Cairo"));
        UserResponse active = kycService.approve(user.publicId(), KycTier.VERIFIED, "test", reviewer());
        userRepository.findByPublicId(active.publicId())
                .ifPresent(u -> createdUserIds.add(u.getId()));
        return active;
    }

    private String openWallet(UserResponse owner) {
        String number = accountService.open(
                new OpenAccountRequest(owner.publicId(), "EGP", AccountType.WALLET, null),
                owner.publicId(), owner.publicId()).accountNumber();
        accountRepository.findByAccountNumber(number)
                .ifPresent(a -> createdAccountIds.add(a.getId()));
        return number;
    }

    private UUID reviewer() {
        return userRepository.findByEmailWithRoles("compliance@paymentwallet.local")
                .orElseThrow(() -> new IllegalStateException("compliance staff not seeded"))
                .getPublicId();
    }
}

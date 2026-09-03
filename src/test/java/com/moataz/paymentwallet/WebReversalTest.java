package com.moataz.paymentwallet;

import com.moataz.paymentwallet.account.AccountRepository;
import com.moataz.paymentwallet.account.AccountService;
import com.moataz.paymentwallet.account.AccountType;
import com.moataz.paymentwallet.account.dto.OpenAccountRequest;
import com.moataz.paymentwallet.transfer.TransferService;
import com.moataz.paymentwallet.transfer.TransferType;
import com.moataz.paymentwallet.transfer.dto.TransferRequest;
import com.moataz.paymentwallet.user.AppUserRepository;
import com.moataz.paymentwallet.user.KycService;
import com.moataz.paymentwallet.user.KycTier;
import com.moataz.paymentwallet.user.UserService;
import com.moataz.paymentwallet.user.dto.KycSubmissionRequest;
import com.moataz.paymentwallet.user.dto.RegisterUserRequest;
import com.moataz.paymentwallet.user.dto.UserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reversal was reachable only through Swagger, which made the one action that best explains an
 * append-only ledger the hardest one to actually show.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WebReversalTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired KycService kycService;
    @Autowired AccountService accountService;
    @Autowired TransferService transferService;
    @Autowired AccountRepository accountRepository;
    @Autowired AppUserRepository userRepository;

    private String from;
    private String to;
    private String reference;

    @BeforeEach
    void setUp() {
        UserResponse owner = verified("reversal");
        from = wallet(owner, AccountType.WALLET, new BigDecimal("500.0000"));
        to = wallet(owner, AccountType.SAVINGS, BigDecimal.ZERO);
        reference = transferService.execute(
                new TransferRequest(from, to, new BigDecimal("120.0000"), "EGP",
                                    TransferType.P2P, "wrong account"),
                UUID.randomUUID().toString(), owner.publicId()).reference();
    }

    @Test
    @DisplayName("the button is on the statement for an admin, and for nobody else")
    void onlyAdminSeesTheButton() throws Exception {
        mockMvc.perform(get("/accounts/{n}/statement", from).accept(MediaType.TEXT_HTML)
                        .with(as(staff("admin@paymentwallet.local"), "ROLE_ADMIN")))
               .andExpect(status().isOk())
               .andExpect(content().string(containsString("/reversal")));

        mockMvc.perform(get("/accounts/{n}/statement", from).accept(MediaType.TEXT_HTML)
                        .with(as(staff("teller@paymentwallet.local"), "ROLE_TELLER")))
               .andExpect(status().isOk())
               .andExpect(content().string(not(containsString("/reversal"))));
    }

    @Test
    @DisplayName("pressing it posts a compensating entry and leaves the original alone")
    void reversingWritesANewEntry() throws Exception {
        mockMvc.perform(post("/accounts/{n}/transfers/{r}/reversal", from, reference)
                        .param("reason", "wrong account")
                        .with(as(staff("admin@paymentwallet.local"), "ROLE_ADMIN")).with(csrf()))
               .andExpect(status().is3xxRedirection());

        assertThat(balanceOf(from)).isEqualByComparingTo("500.0000");
        assertThat(balanceOf(to)).isEqualByComparingTo("0.0000");

        // the original is still on the statement, now marked, with the new entry beside it
        mockMvc.perform(get("/accounts/{n}/statement", from).accept(MediaType.TEXT_HTML)
                        .with(as(staff("admin@paymentwallet.local"), "ROLE_ADMIN")))
               .andExpect(content().string(containsString("REVERSAL")))
               .andExpect(content().string(containsString("reversed")))
               .andExpect(content().string(containsString(reference)));
    }

    @Test
    @DisplayName("a teller cannot reverse: the person who made the mistake should not erase it")
    void tellerCannotReverse() throws Exception {
        mockMvc.perform(post("/accounts/{n}/transfers/{r}/reversal", from, reference)
                        .with(as(staff("teller@paymentwallet.local"), "ROLE_TELLER")).with(csrf()))
               .andExpect(status().isForbidden());

        assertThat(balanceOf(from)).isEqualByComparingTo("380.0000");
    }

    @Test
    @DisplayName("asking twice is refused, and the money moves once")
    void cannotReverseTwice() throws Exception {
        RequestPostProcessor admin = as(staff("admin@paymentwallet.local"), "ROLE_ADMIN");

        mockMvc.perform(post("/accounts/{n}/transfers/{r}/reversal", from, reference)
                        .with(admin).with(csrf()))
               .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/accounts/{n}/transfers/{r}/reversal", from, reference)
                        .with(admin).with(csrf()))
               .andExpect(status().is3xxRedirection());

        assertThat(balanceOf(from)).isEqualByComparingTo("500.0000");
        assertThat(balanceOf(to)).isEqualByComparingTo("0.0000");
    }

    // ---------- fixtures ----------

    private UserResponse verified(String name) {
        UserResponse user = userService.register(new RegisterUserRequest(
                name + "-" + UUID.randomUUID() + "@example.com",
                "+2" + (System.nanoTime() % 1000000000000L), "supersecret1", name));
        kycService.submit(user.publicId(), new KycSubmissionRequest(
                String.valueOf(System.nanoTime() % 100000000000000L),
                LocalDate.of(1990, 1, 1), "Cairo"));
        return kycService.approve(user.publicId(), KycTier.VERIFIED, "test",
                                  staff("compliance@paymentwallet.local"));
    }

    private String wallet(UserResponse holder, AccountType type, BigDecimal balance) {
        String number = accountService.open(
                new OpenAccountRequest(holder.publicId(), "EGP", type, null),
                holder.publicId(), holder.publicId()).accountNumber();
        var account = accountRepository.findByAccountNumber(number).orElseThrow();
        account.setBalance(balance);
        accountRepository.saveAndFlush(account);
        return number;
    }

    private BigDecimal balanceOf(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }

    private UUID staff(String email) {
        return userRepository.findByEmailWithRoles(email)
                .orElseThrow(() -> new IllegalStateException("Staff account missing: " + email))
                .getPublicId();
    }

    private static RequestPostProcessor as(UUID publicId, String role) {
        return jwt().jwt(b -> b.subject(publicId.toString()))
                    .authorities(new SimpleGrantedAuthority(role));
    }
}

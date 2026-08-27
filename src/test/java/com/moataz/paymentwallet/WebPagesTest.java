package com.moataz.paymentwallet;

import com.moataz.paymentwallet.account.*;
import com.moataz.paymentwallet.transfer.CashService;
import com.moataz.paymentwallet.transfer.dto.CashRequest;
import com.moataz.paymentwallet.user.AppUser;
import com.moataz.paymentwallet.user.AppUserRepository;
import com.moataz.paymentwallet.user.Role;
import com.moataz.paymentwallet.user.RoleRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WebPagesTest {

    @Autowired MockMvc mockMvc;
    @Autowired TestDataCleaner testDataCleaner;
    @Autowired CashService cashService;
    @Autowired AccountRepository accountRepository;
    @Autowired AppUserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired CurrencyRepository currencyRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private final List<Long> accountIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    private AppUser alice;
    private AppUser mallory;
    private AppUser teller;
    private Account aliceWallet;

    @BeforeEach
    void setUp() {
        alice = newUser("alice");
        mallory = newUser("mallory");
        teller = newUser("teller");
        aliceWallet = newAccount(alice);
        cashService.deposit(aliceWallet.getAccountNumber(), alice.getPublicId(),
                new CashRequest(new BigDecimal("750.0000"), "opening balance"),
                UUID.randomUUID().toString());
    }

    @AfterEach
    void tearDown() {
        testDataCleaner.deleteCreated(accountIds, userIds);
        accountIds.clear();
        userIds.clear();
    }

    @Test
    @DisplayName("the login page is public and carries a CSRF token")
    void loginPageIsPublic() throws Exception {
        mockMvc.perform(get("/login").accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")))
               .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign in")));
    }

    @Test
    @DisplayName("a browser without a session is redirected to the login page")
    void anonymousBrowserIsRedirected() throws Exception {
        mockMvc.perform(get("/accounts").accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().is3xxRedirection())
               .andExpect(redirectedUrl("/login"));
    }

    @Test
    @DisplayName("a form login sets the auth cookies and redirects to the accounts page")
    void formLoginWorks() throws Exception {
        mockMvc.perform(post("/login")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .param("email", alice.getEmail())
                        .param("password", "alice-pass-1234"))
               .andExpect(status().is3xxRedirection())
               .andExpect(redirectedUrl("/accounts"))
               .andExpect(cookie().exists("access_token"))
               .andExpect(cookie().httpOnly("access_token", true));
    }

    @Test
    @DisplayName("bad credentials re-render the form with an error instead of redirecting")
    void badCredentialsStayOnTheForm() throws Exception {
        mockMvc.perform(post("/login")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .param("email", alice.getEmail())
                        .param("password", "wrong"))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString("incorrect")));
    }

    @Test
    @DisplayName("the accounts page lists the caller's own accounts")
    void accountsPageListsOwnAccounts() throws Exception {
        mockMvc.perform(get("/accounts").with(asUser(alice)).accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString(aliceWallet.getAccountNumber())))
               .andExpect(content().string(org.hamcrest.Matchers.containsString("750.00")));
    }

    @Test
    @DisplayName("the statement page renders the ledger lines and honours filters")
    void statementPageRenders() throws Exception {
        mockMvc.perform(get("/accounts/{n}/statement", aliceWallet.getAccountNumber())
                        .with(asUser(alice)).accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString("SYSTEM-EGP")))
               .andExpect(content().string(org.hamcrest.Matchers.containsString("opening balance")))
               .andExpect(content().string(org.hamcrest.Matchers.containsString("750.00")));

        mockMvc.perform(get("/accounts/{n}/statement", aliceWallet.getAccountNumber())
                        .param("direction", "DEBIT")
                        .with(asUser(alice)).accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString("No entries match these filters")));
    }

    @Test
    @DisplayName("the registration form is public and reports field errors inline")
    void registrationForm() throws Exception {
        mockMvc.perform(get("/register").accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString("Create an account")));

        mockMvc.perform(post("/register")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .param("fullName", "").param("email", "nope")
                        .param("phone", "abc").param("password", "short"))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString("field-error")))
               .andExpect(content().string(org.hamcrest.Matchers.containsString("well-formed email")));
    }

    @Test
    @DisplayName("the customers list is staff only, and activation is admin only")
    void customerAdministrationIsRestricted() throws Exception {
        mockMvc.perform(get("/users").with(asUser(alice)).accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isForbidden());

        mockMvc.perform(get("/users").with(asAdmin()).accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString("registered")));

        mockMvc.perform(post("/users/{id}/activate", alice.getPublicId())
                        .with(asUser(alice))
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf()))
               .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a customer may open their own profile but not someone else's")
    void profileVisibility() throws Exception {
        mockMvc.perform(get("/users/{id}", alice.getPublicId())
                        .with(asUser(alice)).accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString(aliceWallet.getAccountNumber())));

        mockMvc.perform(get("/users/{id}", alice.getPublicId())
                        .with(asUser(mallory)).accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the transfer form lists the caller's accounts and carries a one-time key")
    void transferFormRenders() throws Exception {
        mockMvc.perform(get("/transfer").with(asUser(alice)).accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString(aliceWallet.getAccountNumber())))
               .andExpect(content().string(org.hamcrest.Matchers.containsString("idempotencyKey")));
    }

    @Test
    @DisplayName("submitting the form twice with the same key moves the money once")
    void transferFormIsIdempotent() throws Exception {
        Account target = newAccount(alice);
        String key = UUID.randomUUID().toString();

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/transfer").with(asUser(alice))
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .param("sourceAccountNumber", aliceWallet.getAccountNumber())
                            .param("destAccountNumber", target.getAccountNumber())
                            .param("amount", "50.00")
                            .param("description", "double submit")
                            .param("idempotencyKey", key))
                   .andExpect(status().is3xxRedirection());
        }

        assertThat(accountRepository.findById(target.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("50.0000");
        assertThat(accountRepository.findById(aliceWallet.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("700.0000");
    }

    @Test
    @DisplayName("the form refuses to send from an account the caller does not own")
    void transferFormEnforcesOwnership() throws Exception {
        mockMvc.perform(post("/transfer").with(asUser(mallory))
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .param("sourceAccountNumber", aliceWallet.getAccountNumber())
                        .param("destAccountNumber", aliceWallet.getAccountNumber())
                        .param("amount", "10.00")
                        .param("idempotencyKey", UUID.randomUUID().toString()))
               .andExpect(status().isForbidden());

        assertThat(accountRepository.findById(aliceWallet.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("750.0000");
    }

    @Test
    @DisplayName("a rejected transfer re-renders the form with the reason")
    void transferFormShowsBusinessErrors() throws Exception {
        Account target = newAccount(alice);

        mockMvc.perform(post("/transfer").with(asUser(alice))
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .param("sourceAccountNumber", aliceWallet.getAccountNumber())
                        .param("destAccountNumber", target.getAccountNumber())
                        .param("amount", "99999.00")
                        .param("idempotencyKey", UUID.randomUUID().toString()))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString("Insufficient funds")));

        mockMvc.perform(post("/transfer").with(asUser(alice))
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .param("sourceAccountNumber", aliceWallet.getAccountNumber())
                        .param("destAccountNumber", "PW9999999999999999")
                        .param("amount", "10.00")
                        .param("idempotencyKey", UUID.randomUUID().toString()))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString("No account with that number")));
    }

    @Test
    @DisplayName("a denied page renders HTML, while the API keeps returning problem+json")
    void deniedPagesRenderHtml() throws Exception {
        mockMvc.perform(get("/accounts/{n}/statement", aliceWallet.getAccountNumber())
                        .with(asUser(mallory)).accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isForbidden())
               .andExpect(content().string(org.hamcrest.Matchers.containsString("<!DOCTYPE html>")))
               .andExpect(content().string(org.hamcrest.Matchers.containsString("Not yours")));

        mockMvc.perform(get("/api/accounts/{n}/statement", aliceWallet.getAccountNumber())
                        .with(asUser(mallory)))
               .andExpect(status().isForbidden())
               .andExpect(content().string(org.hamcrest.Matchers.containsString("\"title\":\"Access denied\"")));
    }

    @Test
    @DisplayName("a teller can read a customer's statement page")
    void tellerReadsStatement() throws Exception {
        mockMvc.perform(get("/accounts/{n}/statement", aliceWallet.getAccountNumber())
                        .with(asTeller()).accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString(aliceWallet.getAccountNumber())));
    }

    @Test
    @DisplayName("Send money appears only for someone who owns an account")
    void sendMoneyNavIsDataDriven() throws Exception {
        mockMvc.perform(get("/accounts").with(asUser(alice)).accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString(">Send money<")));

        mockMvc.perform(get("/accounts").with(asTeller()).accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.not(
                       org.hamcrest.Matchers.containsString(">Send money<"))));
    }

    @Test
    @DisplayName("the cash desk is staff only")
    void cashDeskIsStaffOnly() throws Exception {
        mockMvc.perform(get("/cash").with(asUser(alice)).accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isForbidden());

        mockMvc.perform(post("/cash/deposit").with(asUser(alice))
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .param("accountNumber", aliceWallet.getAccountNumber())
                        .param("amount", "1000000")
                        .param("idempotencyKey", UUID.randomUUID().toString()))
               .andExpect(status().isForbidden());

        assertThat(accountRepository.findById(aliceWallet.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("750.0000");
    }

    @Test
    @DisplayName("a teller can take cash in and pay it out, once per key")
    void tellerTakesAndPaysCash() throws Exception {
        String key = UUID.randomUUID().toString();

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/cash/deposit").with(asTeller())
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .param("accountNumber", aliceWallet.getAccountNumber())
                            .param("amount", "200.00")
                            .param("description", "counter cash")
                            .param("idempotencyKey", key))
                   .andExpect(status().is3xxRedirection());
        }
        assertThat(accountRepository.findById(aliceWallet.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("950.0000");

        mockMvc.perform(post("/cash/withdraw").with(asTeller())
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .param("accountNumber", aliceWallet.getAccountNumber())
                        .param("amount", "150.00")
                        .param("idempotencyKey", UUID.randomUUID().toString()))
               .andExpect(status().is3xxRedirection());
        assertThat(accountRepository.findById(aliceWallet.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("800.0000");
    }

    @Test
    @DisplayName("the cash desk refuses a settlement account and reports overdrafts inline")
    void cashDeskGuards() throws Exception {
        mockMvc.perform(get("/cash").param("account", "SYSTEM-EGP").with(asTeller())
                        .accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString("settlement account")));

        mockMvc.perform(post("/cash/withdraw").with(asTeller())
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .param("accountNumber", aliceWallet.getAccountNumber())
                        .param("amount", "999999.00")
                        .param("idempotencyKey", UUID.randomUUID().toString()))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString("Insufficient funds")));
    }

    @Test
    @DisplayName("the admin operations page is admin only")
    void adminPageIsAdminOnly() throws Exception {
        mockMvc.perform(get("/admin").with(asUser(alice)).accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin").with(asAdmin()).accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString("Operations")));
    }

    @Test
    @DisplayName("staff see every account on the accounts page, a customer sees only their own")
    void staffSeeEveryAccount() throws Exception {
        mockMvc.perform(get("/accounts").with(asAdmin()).accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString("Customers")))
               .andExpect(content().string(org.hamcrest.Matchers.containsString("Settlement accounts")))
               .andExpect(content().string(org.hamcrest.Matchers.containsString("SYSTEM-EGP")));

        mockMvc.perform(get("/accounts").with(asUser(mallory)).accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString("Your accounts")))
               .andExpect(content().string(org.hamcrest.Matchers.not(
                       org.hamcrest.Matchers.containsString(aliceWallet.getAccountNumber()))));
    }

    @Test
    @DisplayName("Mallory cannot open the statement page for Alice's account")
    void statementPageEnforcesOwnership() throws Exception {
        mockMvc.perform(get("/accounts/{n}/statement", aliceWallet.getAccountNumber())
                        .with(asUser(mallory)).accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isForbidden());
    }

    private RequestPostProcessor asTeller() {
        return jwt()
                .jwt(builder -> builder
                        .subject(teller.getPublicId().toString())
                        .claim("email", teller.getEmail())
                        .claim("roles", List.of("ROLE_TELLER")))
                .authorities(new SimpleGrantedAuthority("ROLE_TELLER"));
    }

    private RequestPostProcessor asAdmin() {
        return jwt()
                .jwt(builder -> builder
                        .subject(UUID.randomUUID().toString())
                        .claim("email", "admin@paymentwallet.local")
                        .claim("roles", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private RequestPostProcessor asUser(AppUser user) {
        return jwt()
                .jwt(builder -> builder
                        .subject(user.getPublicId().toString())
                        .claim("email", user.getEmail())
                        .claim("roles", List.of("ROLE_CUSTOMER")))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private AppUser newUser(String name) {
        AppUser user = new AppUser();
        user.setEmail(name + "-" + UUID.randomUUID() + "@example.com");
        user.setPhone("+9" + (System.nanoTime() % 1000000000000L));
        user.setFullName(name);
        user.setPasswordHash(passwordEncoder.encode(name + "-pass-1234"));
        user.addRole(roleRepository.findByName(Role.CUSTOMER).orElseThrow());
        AppUser saved = userRepository.saveAndFlush(user);
        userIds.add(saved.getId());
        return saved;
    }

    private Account newAccount(AppUser owner) {
        Account account = new Account();
        account.setAccountNumber("PW" + String.format("%016d", System.nanoTime() % 10000000000000000L));
        account.setUser(owner);
        account.setCurrency(currencyRepository.findById("EGP").orElseThrow());
        account.setType(AccountType.WALLET);
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(BigDecimal.ZERO);
        Account saved = accountRepository.saveAndFlush(account);
        accountIds.add(saved.getId());
        return saved;
    }
}

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
    private Account aliceWallet;

    @BeforeEach
    void setUp() {
        alice = newUser("alice");
        mallory = newUser("mallory");
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
    @DisplayName("Mallory cannot open the statement page for Alice's account")
    void statementPageEnforcesOwnership() throws Exception {
        mockMvc.perform(get("/accounts/{n}/statement", aliceWallet.getAccountNumber())
                        .with(asUser(mallory)).accept(org.springframework.http.MediaType.TEXT_HTML))
               .andExpect(status().isForbidden());
    }

    private RequestPostProcessor asUser(AppUser user) {
        return jwt().jwt(builder -> builder
                .subject(user.getPublicId().toString())
                .claim("email", user.getEmail())
                .claim("roles", List.of("ROLE_CUSTOMER")));
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

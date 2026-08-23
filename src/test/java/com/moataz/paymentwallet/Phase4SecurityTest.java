package com.moataz.paymentwallet;

import com.jayway.jsonpath.JsonPath;
import com.moataz.paymentwallet.account.*;
import com.moataz.paymentwallet.auth.TokenService;
import com.moataz.paymentwallet.transfer.*;
import com.moataz.paymentwallet.user.AppUser;
import com.moataz.paymentwallet.user.AppUserRepository;
import com.moataz.paymentwallet.user.Role;
import com.moataz.paymentwallet.user.RoleRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 4 acceptance. Authentication proves who you are; these tests prove the part
 * that matters more — that being authenticated is not enough to touch someone else's
 * money.
 */
@SpringBootTest
@AutoConfigureMockMvc
class Phase4SecurityTest {

    @Autowired MockMvc mockMvc;
    @Autowired TokenService tokenService;
    @Autowired TestDataCleaner testDataCleaner;
    @Autowired AppUserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired CurrencyRepository currencyRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private final List<Long> accountIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    private AppUser alice;
    private AppUser mallory;
    private Account aliceWallet;
    private String aliceToken;
    private String malloryToken;

    @BeforeEach
    void setUp() {
        alice = newUser("alice", "alice-pass-1234");
        mallory = newUser("mallory", "mallory-pass-1234");
        aliceWallet = newAccount(alice, new BigDecimal("500.0000"));
        newAccount(mallory, BigDecimal.ZERO);
        aliceToken = tokenService.issueAccessToken(alice);
        malloryToken = tokenService.issueAccessToken(mallory);
    }

    @AfterEach
    void tearDown() {
        testDataCleaner.deleteCreated(accountIds, userIds);
        accountIds.clear();
        userIds.clear();
    }

    @Test
    @DisplayName("no token is 401, not 403")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get("/api/accounts/" + aliceWallet.getAccountNumber()))
               .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/accounts/" + aliceWallet.getAccountNumber() + "/statement"))
               .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("login issues a usable token; bad credentials do not reveal whether the user exists")
    void loginWorks() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "alice-pass-1234"}
                                """.formatted(alice.getEmail())))
               .andExpect(status().isOk())
               .andExpect(cookie().exists("access_token"))
               .andExpect(cookie().httpOnly("access_token", true))
               .andExpect(cookie().exists("refresh_token"))
               .andExpect(cookie().httpOnly("refresh_token", true))
               .andExpect(jsonPath("$.accessToken").exists())
               .andExpect(jsonPath("$.roles[0]").value("ROLE_CUSTOMER"))
               .andReturn().getResponse().getContentAsString();

        String token = JsonPath.read(body, "$.accessToken");
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.email").value(alice.getEmail()));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "wrong-password"}
                                """.formatted(alice.getEmail())))
               .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "nobody@example.com", "password": "wrong-password"}
                                """))
               .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Mallory cannot read Alice's account or statement")
    void cannotReadSomeoneElsesAccount() throws Exception {
        mockMvc.perform(get("/api/accounts/" + aliceWallet.getAccountNumber())
                        .header("Authorization", "Bearer " + malloryToken))
               .andExpect(status().isForbidden())
               .andExpect(jsonPath("$.title").value("Access denied"));

        mockMvc.perform(get("/api/accounts/" + aliceWallet.getAccountNumber() + "/statement")
                        .header("Authorization", "Bearer " + malloryToken))
               .andExpect(status().isForbidden());

        // and Alice still can
        mockMvc.perform(get("/api/accounts/" + aliceWallet.getAccountNumber())
                        .header("Authorization", "Bearer " + aliceToken))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.balance").value(500.0));
    }

    @Test
    @DisplayName("Mallory cannot transfer out of Alice's wallet, and the balance is untouched")
    void cannotSpendSomeoneElsesMoney() throws Exception {
        Account malloryWallet = accountRepository.findAllByOwner(mallory.getPublicId()).getFirst();

        String request = """
                {"sourceAccountNumber": "%s", "destAccountNumber": "%s",
                 "amount": 100.0000, "currencyCode": "EGP", "type": "P2P"}
                """.formatted(aliceWallet.getAccountNumber(), malloryWallet.getAccountNumber());

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + malloryToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
               .andExpect(status().isForbidden());

        // the assertion that matters: an authorization bug that still moves money is worse
        assertThat(accountRepository.findById(aliceWallet.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("500.0000");
        assertThat(accountRepository.findById(malloryWallet.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("Mallory cannot deposit into Alice's wallet, and activation is admin only")
    void cannotOperateOnSomeoneElsesWallet() throws Exception {
        mockMvc.perform(post("/api/accounts/" + aliceWallet.getAccountNumber() + "/deposits")
                        .header("Authorization", "Bearer " + malloryToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 50.0000}"))
               .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/users/" + alice.getPublicId() + "/activation")
                        .header("Authorization", "Bearer " + malloryToken))
               .andExpect(status().isForbidden());

        assertThat(accountRepository.findById(aliceWallet.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("500.0000");
    }

    @Test
    @DisplayName("a refresh token is single use, and replaying it kills the whole family")
    void refreshRotatesAndDetectsReuse() throws Exception {
        String first = tokenService.issueRefreshToken(alice);

        TokenService.RotationResult ok = tokenService.rotate(first);
        assertThat(ok.accepted()).isTrue();
        assertThat(ok.refreshToken()).isNotEqualTo(first);

        // replaying the spent token is the signal that a copy leaked
        TokenService.RotationResult replay = tokenService.rotate(first);
        assertThat(replay.accepted()).isFalse();
        assertThat(replay.reason()).contains("reuse");

        // the token issued by the legitimate rotation is revoked along with the family
        TokenService.RotationResult afterFamilyRevoke = tokenService.rotate(ok.refreshToken());
        assertThat(afterFamilyRevoke.accepted()).isFalse();
    }

    // ---------- fixtures ----------

    private AppUser newUser(String name, String password) {
        AppUser user = new AppUser();
        user.setEmail(name + "-" + UUID.randomUUID() + "@example.com");
        user.setPhone("+6" + (System.nanoTime() % 1000000000000L));
        user.setFullName(name);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.addRole(roleRepository.findByName(Role.CUSTOMER).orElseThrow());
        AppUser saved = userRepository.saveAndFlush(user);
        userIds.add(saved.getId());
        return saved;
    }

    private Account newAccount(AppUser owner, BigDecimal balance) {
        Account account = new Account();
        account.setAccountNumber("PW" + String.format("%016d", System.nanoTime() % 10000000000000000L));
        account.setUser(owner);
        account.setCurrency(currencyRepository.findById("EGP").orElseThrow());
        account.setType(AccountType.WALLET);
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(balance);
        Account saved = accountRepository.saveAndFlush(account);
        accountIds.add(saved.getId());
        return saved;
    }
}

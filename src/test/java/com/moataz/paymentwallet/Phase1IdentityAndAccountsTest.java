package com.moataz.paymentwallet;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 1 acceptance: register a user, open an EGP wallet, and prove that every
 * validation failure comes back as a clean RFC 7807 document.
 *
 * @Transactional on the test class rolls everything back afterwards, so running
 * this repeatedly never pollutes your dev database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Phase1IdentityAndAccountsTest {

    @Autowired MockMvc mockMvc;
    @Autowired com.moataz.paymentwallet.user.UserService userService;

    private static final String VALID_USER = """
            {
              "email": "mona@example.com",
              "phone": "+201234567890",
              "password": "supersecret1",
              "fullName": "Mona Ali"
            }
            """;

    @Test
    @DisplayName("register returns 201, a public id, the CUSTOMER role, and never the password")
    void registersUser() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_USER))
               .andExpect(status().isCreated())
               .andExpect(header().exists("Location"))
               .andExpect(jsonPath("$.publicId").exists())
               .andExpect(jsonPath("$.email").value("mona@example.com"))
               .andExpect(jsonPath("$.status").value("PENDING"))
               .andExpect(jsonPath("$.roles[0]").value("ROLE_CUSTOMER"))
               .andExpect(jsonPath("$.passwordHash").doesNotExist())
               .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    @DisplayName("invalid fields produce one 400 ProblemDetail listing every bad field")
    void rejectsInvalidRegistration() throws Exception {
        String bad = """
                {"email": "not-an-email", "phone": "abc", "password": "short", "fullName": ""}
                """;

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bad))
               .andExpect(status().isBadRequest())
               .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
               .andExpect(jsonPath("$.title").value("Validation failed"))
               .andExpect(jsonPath("$.status").value(400))
               .andExpect(jsonPath("$.errors.email").exists())
               .andExpect(jsonPath("$.errors.phone").exists())
               .andExpect(jsonPath("$.errors.password").exists())
               .andExpect(jsonPath("$.errors.fullName").exists());
    }

    @Test
    @DisplayName("email uniqueness ignores case: the same mailbox cannot register twice")
    void emailIsCaseInsensitive() throws Exception {
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(VALID_USER))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.email").value("mona@example.com"));

        String shouted = """
                {"email": "  MONA@Example.COM ", "phone": "+201234567891",
                 "password": "supersecret1", "fullName": "Mona Again"}
                """;
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(shouted))
               .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a duplicate email is a 409, not a 500 stack trace")
    void rejectsDuplicateEmail() throws Exception {
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(VALID_USER))
               .andExpect(status().isCreated());

        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(VALID_USER))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.title").value("Resource already exists"));
    }

    @Test
    @DisplayName("open an EGP wallet: 201, balance 0, and it shows up in the owner's list")
    void opensEgpWallet() throws Exception {
        String publicId = registerAndGetPublicId();

        String request = """
                {"currencyCode": "EGP", "type": "WALLET", "dailyLimit": 5000.0000}
                """;

        String body = mockMvc.perform(post("/api/accounts")
                        .with(asUser(publicId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.accountNumber").exists())
               .andExpect(jsonPath("$.currencyCode").value("EGP"))
               .andExpect(jsonPath("$.type").value("WALLET"))
               .andExpect(jsonPath("$.status").value("ACTIVE"))
               .andExpect(jsonPath("$.balance").value(0))
               .andExpect(jsonPath("$.ownerPublicId").value(publicId))
               .andReturn().getResponse().getContentAsString();

        String accountNumber = JsonPath.read(body, "$.accountNumber");

        mockMvc.perform(get("/api/accounts").with(asUser(publicId)))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.content.length()").value(1))
               .andExpect(jsonPath("$.content[0].accountNumber").value(accountNumber));
    }

    @Test
    @DisplayName("a PENDING user cannot open an account until an administrator activates them")
    void pendingUserCannotOpenAnAccount() throws Exception {
        String body = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_USER))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.status").value("PENDING"))
               .andReturn().getResponse().getContentAsString();
        String publicId = JsonPath.read(body, "$.publicId");

        mockMvc.perform(post("/api/accounts")
                        .with(asUser(publicId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currencyCode": "EGP", "type": "WALLET"}
                                """))
               .andExpect(status().isUnprocessableEntity())
               .andExpect(jsonPath("$.detail").value(
                       org.hamcrest.Matchers.containsString("identity must be verified")));

        userService.activate(java.util.UUID.fromString(publicId));

        mockMvc.perform(post("/api/accounts")
                        .with(asUser(publicId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currencyCode": "EGP", "type": "WALLET"}
                                """))
               .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("an unknown currency is a 404 ProblemDetail")
    void rejectsUnknownCurrency() throws Exception {
        String publicId = registerAndGetPublicId();

        String request = """
                {"currencyCode": "XXX", "type": "WALLET"}
                """;

        mockMvc.perform(post("/api/accounts")
                        .with(asUser(publicId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
               .andExpect(status().isNotFound())
               .andExpect(jsonPath("$.title").value("Resource not found"))
               .andExpect(jsonPath("$.detail").value("Unknown currency: XXX"));
    }

    /** Authenticates as this user without calling /api/auth/login. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor asUser(String publicId) {
        return jwt().jwt(builder -> builder.subject(publicId)
                                           .claim("roles", java.util.List.of("ROLE_CUSTOMER")));
    }

    private String registerAndGetPublicId() throws Exception {
        String body = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_USER))
               .andExpect(status().isCreated())
               .andReturn().getResponse().getContentAsString();
        String publicId = JsonPath.read(body, "$.publicId");
        // registration leaves the user PENDING; activation is what unlocks accounts and money
        userService.activate(java.util.UUID.fromString(publicId));
        return publicId;
    }
}

package com.moataz.paymentwallet;

import com.moataz.paymentwallet.user.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * A permission you hold but cannot reach is still a bug.
 *
 * Authorization tests only ever ask "is this allowed?", and every one of them passed while a
 * supervisor could open the cash desk by typing the URL and had no link to it on any page. The
 * cause was three separate definitions of the word "staff" — one in AccountOwnership for
 * @PreAuthorize, one in CurrentUser for the page flags, one hardcoded in the navigation — which
 * drifted apart the moment SUPERVISOR was added to only the first.
 *
 * So this asserts the two agree, for every staff role, rather than asserting either alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffNavigationTest {

    private static final Pattern NAV = Pattern.compile("<nav class=\"nav\">(.*?)</nav>", Pattern.DOTALL);

    @Autowired MockMvc mockMvc;
    @Autowired AppUserRepository userRepository;

    @ParameterizedTest(name = "{0} reaches every page it is allowed to use")
    @CsvSource({
            "ROLE_TELLER,     teller@paymentwallet.local",
            "ROLE_SUPERVISOR, supervisor@paymentwallet.local",
            "ROLE_ADMIN,      admin@paymentwallet.local",
            "ROLE_COMPLIANCE, compliance@paymentwallet.local",
            "ROLE_AUDITOR,    auditor@paymentwallet.local"
    })
    @DisplayName("whatever a role may open, its navigation links to")
    void navigationMatchesPermission(String role, String email) throws Exception {
        RequestPostProcessor actor = as(email, role);
        String nav = navOf(actor);

        assertLinkMatchesAccess(role, nav, actor, "/cash");
        assertLinkMatchesAccess(role, nav, actor, "/users");
    }

    private void assertLinkMatchesAccess(String role, String nav,
                                         RequestPostProcessor actor, String path) throws Exception {
        boolean allowed = mockMvc.perform(get(path).with(actor).accept(MediaType.TEXT_HTML))
                                 .andReturn().getResponse().getStatus() == 200;
        boolean linked = nav.contains("href=\"" + path + "\"");

        assertThat(linked)
                .describedAs("%s may %s open %s, so the navigation should %s link to it",
                             role, allowed ? "" : "not", path, allowed ? "" : "not")
                .isEqualTo(allowed);
    }

    private String navOf(RequestPostProcessor actor) throws Exception {
        String html = mockMvc.perform(get("/accounts").with(actor).accept(MediaType.TEXT_HTML))
                             .andReturn().getResponse().getContentAsString();
        Matcher matcher = NAV.matcher(html);
        assertThat(matcher.find()).describedAs("the page renders the shared layout").isTrue();
        return matcher.group(1);
    }

    private RequestPostProcessor as(String email, String role) {
        UUID publicId = userRepository.findByEmailWithRoles(email.trim())
                .orElseThrow(() -> new IllegalStateException("Staff account missing: " + email))
                .getPublicId();
        return jwt().jwt(builder -> builder.subject(publicId.toString()))
                    .authorities(new SimpleGrantedAuthority(role.trim()));
    }
}

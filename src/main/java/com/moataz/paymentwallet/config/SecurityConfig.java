package com.moataz.paymentwallet.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Stateless resource server. The access token is a signed JWT carried in an HttpOnly
 * cookie (so a cross-site script cannot read it) with the Authorization header accepted
 * as a fallback, which is what lets Swagger and curl work by hand.
 *
 * Cookies are sent by the browser automatically, so CSRF protection matters here in a way
 * it would not for a header-only API. SameSite plus Spring's double-submit cookie token
 * cover it; GET endpoints are exempt because they change nothing.
 */
@Configuration
@EnableMethodSecurity          // turns on @PreAuthorize
public class SecurityConfig {

    public static final String ACCESS_COOKIE = "access_token";
    public static final String REFRESH_COOKIE = "refresh_token";
    public static final String REFRESH_PATH = "/api/auth/refresh";

    @Value("${security.jwt.secret}")
    private String jwtSecret;

    @Value("${security.cors.allowed-origins:http://localhost:3000}")
    private List<String> allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(Customizer.withDefaults())
            // CSRF is on for the browser pages, because cookies are attached automatically
            // and a form post from another origin would otherwise be authenticated. The
            // JSON API is exempt: its clients send an explicit header, which no cross-site
            // form can do. The cookie repository is used rather than the session one
            // because this filter chain is stateless.
            .csrf(csrf -> csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .ignoringRequestMatchers("/api/**"))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/auth/login", "/api/auth/refresh").permitAll()
                    .requestMatchers("/login", "/css/**", "/favicon.ico").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/users").permitAll()
                    .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    .anyRequest().authenticated())
            // a browser asking for HTML should be sent to the sign-in page; an API client
            // asking for JSON should get 401 and no redirect
            .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/login"), htmlRequestMatcher()))
            .oauth2ResourceServer(oauth -> oauth
                    .bearerTokenResolver(cookieOrHeaderTokenResolver())
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /**
     * Matches only a request that explicitly asks for HTML. Without ignoring MediaType.ALL,
     * a request with no Accept header counts as "*​/*", which is compatible with text/html —
     * so an API call carrying no Accept would be redirected to the login page instead of
     * getting a 401.
     */
    private MediaTypeRequestMatcher htmlRequestMatcher() {
        MediaTypeRequestMatcher matcher = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
        matcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        return matcher;
    }

    /** Reads the JWT from the HttpOnly cookie first, then falls back to Authorization: Bearer. */
    @Bean
    public BearerTokenResolver cookieOrHeaderTokenResolver() {
        var header = new org.springframework.security.oauth2.server.resource.web
                .DefaultBearerTokenResolver();
        return (HttpServletRequest request) -> {
            Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                String fromCookie = Arrays.stream(cookies)
                        .filter(c -> ACCESS_COOKIE.equals(c.getName()))
                        .map(Cookie::getValue)
                        .findFirst().orElse(null);
                if (fromCookie != null && !fromCookie.isBlank()) {
                    return fromCookie;
                }
            }
            return header.resolve(request);
        };
    }

    /** Maps the "roles" claim onto Spring authorities. Names already carry the ROLE_ prefix. */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            return roles == null ? List.<GrantedAuthority>of()
                    : roles.stream().map(r -> (GrantedAuthority) new SimpleGrantedAuthority(r)).toList();
        });
        return converter;
    }

    private SecretKeySpec secretKey() {
        return new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey()));
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(secretKey()).build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);      // never "*" together with credentials
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

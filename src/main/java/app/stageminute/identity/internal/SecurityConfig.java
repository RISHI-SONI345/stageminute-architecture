package app.stageminute.identity.internal;

import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

/**
 * Sign-in on the server (AD-53, 10-AUTH-CLERK section 3). There is no login here: the app signs in at Clerk and
 * sends the Clerk session token as {@code Authorization: Bearer}. This class decides which requests need that
 * token (all, except health and the Clerk webhook) and how the token is checked.
 *
 * <p>Choices that keep it safe:
 * <ul>
 *   <li>Deny by default: a new endpoint is closed until someone opens it on purpose.
 *   <li>Stateless: no cookie, no session, so no CSRF to defend (CSRF is a cookie attack).
 *   <li>Only RS256: a token that says {@code alg: none} or {@code HS256} is refused before anything else.
 *   <li>Keys come from Clerk's JWKS address over https, with short timeouts, cached by the library.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClerkProperties.class)
class SecurityConfig {

    private static final Duration KEY_FETCH_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SessionProblemEntryPoint entryPoint, JwtDecoder decoder)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // Only health and info are open. Anything else an operator might expose later (metrics,
                        // the module map) stays behind sign-in until someone opens it here on purpose.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        // Clerk calls this without a session token; it proves itself with a Svix signature.
                        .requestMatchers(HttpMethod.POST, "/v1/webhooks/clerk")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(
                        resource -> resource.jwt(jwt -> jwt.decoder(decoder)).authenticationEntryPoint(entryPoint))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint))
                .headers(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    JwtDecoder clerkJwtDecoder(ClerkProperties clerk, Clock clock) {
        clerk.requireUsable();
        SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
        http.setConnectTimeout(KEY_FETCH_TIMEOUT);
        http.setReadTimeout(KEY_FETCH_TIMEOUT);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(clerk.jwksUrl())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(http))
                .build();
        decoder.setJwtValidator(ClerkTokenValidators.create(clerk.issuer(), clerk.authorizedParties(), clock));
        return decoder;
    }
}

package utp.siga.evidence.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter converter,
            ObjectMapper mapper) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/evidences/*/content", "/api/v1/evidences/*").hasAuthority("EVIDENCE_READ")
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/evidences").hasAuthority("EVIDENCE_READ")
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/evidences").hasAuthority("EVIDENCE_WRITE")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, failure) ->
                                writeProblem(mapper, request, response, 401, "AUTH_UNAUTHORIZED", "Autenticación requerida")))
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler((request, response, failure) ->
                        writeProblem(mapper, request, response, 403, "AUTH_FORBIDDEN", "Permiso insuficiente")))
                .build();
    }

    @Bean
    NimbusJwtDecoder jwtDecoder(
            @Value("${evidence.security.jwks-uri}") String jwksUri,
            @Value("${evidence.security.issuer}") String issuer,
            @Value("${evidence.security.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>("aud",
                values -> values != null && values.contains(audience));
        OAuth2TokenValidator<Jwt> subjectValidator = jwt -> {
            try {
                UUID.fromString(jwt.getSubject());
                return OAuth2TokenValidatorResult.success();
            } catch (RuntimeException e) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "sub debe ser UUID", null));
            }
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                audienceValidator, subjectValidator));
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("permissions");
        authorities.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private static void writeProblem(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response,
            int status, String code, String detail) throws IOException {
        UUID correlation = correlation(request.getHeader("X-Correlation-Id"));
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Map.of(
                "type", "https://siga.local/problems/" + code.toLowerCase().replace('_', '-'),
                "title", status == 401 ? "Unauthorized" : "Forbidden",
                "status", status,
                "detail", detail,
                "instance", request.getRequestURI(),
                "code", code,
                "timestamp", Instant.now().toString(),
                "correlationId", correlation.toString()));
    }

    private static UUID correlation(String value) {
        try { return value == null ? UUID.randomUUID() : UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return UUID.randomUUID(); }
    }
}

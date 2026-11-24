package com.agentgate.gateway.identity;

import com.agentgate.gateway.shared.web.ProblemDetailsSecurityHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

/**
 * Who may call what (course rule C3: security on every endpoint). Four filter chains, checked in @Order:
 *
 * 1. /oauth2/token, /oauth2/jwks …  Spring Authorization Server: agents trade client id + secret for a JWT.
 * 2. /api/admin/**                  admin only, HTTP Basic.
 * 3. /api/agent/**                  agents only, "Authorization: Bearer <JWT>" (resource server).
 * 4. everything else                health + Swagger UI open, the rest denied (default deny).
 */
@Configuration
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer = new OAuth2AuthorizationServerConfigurer();
        http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .csrf(csrf -> csrf.ignoringRequestMatchers(authorizationServer.getEndpointsMatcher()));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain adminChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        var problems = new ProblemDetailsSecurityHandler(objectMapper,
                ProblemDetailsSecurityHandler.basicChallenge("AgentGate admin"));
        http.securityMatcher("/api/admin/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
                .httpBasic(basic -> basic.authenticationEntryPoint(problems))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // No cookies/sessions → a browser cannot be tricked into sending the login, so CSRF tokens are not needed.
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain agentChain(HttpSecurity http, ObjectMapper objectMapper, AgentRepository agentRepository)
            throws Exception {
        var problems = new ProblemDetailsSecurityHandler(objectMapper, new BearerTokenAuthenticationEntryPoint());
        http.securityMatcher("/api/agent/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("AGENT"))
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new AgentJwtAuthenticationConverter(agentRepository)))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    @Order(4)
    SecurityFilterChain everythingElseChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        var problems = new ProblemDetailsSecurityHandler(objectMapper, (request, response, ex) -> { });
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /** BCrypt by default; stored hashes look like "{bcrypt}$2a$10$...". Used for agent secrets and the admin password. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** The single admin account, from environment variables (no password in the repo outside local defaults). */
    @Bean
    UserDetailsService adminUsers(GatewaySecurityProperties properties, PasswordEncoder passwordEncoder) {
        var admin = User.withUsername(properties.admin().username())
                .password(passwordEncoder.encode(properties.admin().password()))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(admin);
    }
}

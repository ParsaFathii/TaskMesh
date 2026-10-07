package com.taskmesh.config;

import com.taskmesh.security.JwtAuthenticationFilter;
import com.taskmesh.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Stateless security: JWT bearer authentication with URL-level role rules (SPEC §11).
 * Object-level rules (own vs. other users' resources) are enforced in the service layer.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final String PROBLEM_JSON = "application/problem+json";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtAuthenticationFilter jwtFilter,
                                            CorsConfigurationSource corsSource,
                                            ObjectMapper objectMapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsSource))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/health", "/api/v1/auth/login").permitAll()
                        // The raw WebSocket endpoint authenticates the JWT query parameter itself.
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/users").hasRole("ADMIN")
                        .requestMatchers("/api/v1/workers/**").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/logs").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/metrics").hasAnyRole("OPERATOR", "ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, ex1) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED",
                                        "Authentication required", objectMapper))
                        .accessDeniedHandler((request, response, ex1) ->
                                writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                                        "Insufficient permissions", objectMapper)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @Primary
    CorsConfigurationSource corsConfigurationSource(TaskMeshProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        String[] origins = properties.corsOriginArray();
        if (origins.length > 0) {
            config.setAllowedOrigins(Arrays.asList(origins));
        }
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id"));
        config.setExposedHeaders(List.of("X-Request-Id", "X-Job-Result-SHA256", "Location"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    JwtService jwtService(TaskMeshProperties properties) {
        return new JwtService(properties);
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message,
                                   ObjectMapper objectMapper) {
        try {
            response.setStatus(status);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            String body = objectMapper.writeValueAsString(
                    java.util.Map.of("error", java.util.Map.of("code", code, "message", message)));
            response.getWriter().write(body);
        } catch (Exception ignored) {
            // The response is already committed or the client is gone; nothing to do.
        }
    }
}

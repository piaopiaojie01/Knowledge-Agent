package com.ka.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 配置
 * - CORS 白名单从 AppConstants（→ application.yml）注入
 * - JWT 无状态认证
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    @Lazy
    private final JwtAuthFilter jwtAuthFilter;

    private final AppConstants constants;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                // 未认证（含禁用/强制登出后的存量 token）统一返回 401 JSON，
                // 前端拦截器据此自动刷新或跳回登录页
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":401,\"message\":\"未登录或登录已失效\"}");
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(403);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":403,\"message\":\"无权限执行此操作\"}");
                }))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/static/**", "/charts/**", "/icons/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                .requestMatchers("/api/health", "/api/auth/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/kb/**").authenticated()
                .requestMatchers("/api/docs/**").authenticated()
                .requestMatchers("/api/rag/**").authenticated()
                .anyRequest().authenticated())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager(
            User.withUsername("__placeholder__")
                .password("{noop}__placeholder__")
                .roles("NONE")
                .build()
        );
    }

    /** CORS 白名单：从 AppConstants（application.yml）注入 */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> allowedOrigins = constants.getCorsOrigins();
        CorsConfiguration config = new CorsConfiguration();
        if (constants.isCorsAllowTrycloudflare()) {
            // 演示专用：兼容 Cloudflare Quick Tunnel 的随机子域名（普通白名单不支持通配，用 pattern）
            List<String> patterns = new java.util.ArrayList<>(allowedOrigins);
            patterns.add("https://*.trycloudflare.com");
            config.setAllowedOriginPatterns(patterns);
        } else {
            config.setAllowedOrigins(allowedOrigins);
        }
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }
}

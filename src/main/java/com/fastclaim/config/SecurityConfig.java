package com.fastclaim.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.Customizer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
// 仅在非测试环境下加载 — 测试和 e2e 需要绕过认证，避免每个测试用例都要构造 HTTP Basic 头
@Profile("!test & !e2e")
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        log.info("初始化安全过滤器链 — CSRF 已禁用，开放 /swagger-ui、/v3/api-docs、/api/insurance/health");
        http
            // 关闭 CSRF — API 服务无表单提交场景，保留会增加不必要的复杂度
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()
                .requestMatchers("/api/insurance/health").permitAll()
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        log.info("初始化内存用户 — user/underwriter/claims/admin 四角色");

        var user = User.withUsername("user")
                .password(passwordEncoder.encode("password"))
                .roles("USER")
                .authorities("underwriting:read", "chat:use", "policies:read")
                .build();

        var underwriter = User.withUsername("underwriter")
                .password(passwordEncoder.encode("underwriter"))
                .roles("UNDERWRITER")
                .authorities("underwriting:read", "chat:use", "policies:read",
                        "underwriting:write", "underwriting:approve")
                .build();

        var claims = User.withUsername("claims")
                .password(passwordEncoder.encode("claims"))
                .roles("CLAIMS")
                .authorities("underwriting:read", "chat:use", "policies:read",
                        "claims:write", "claims:read", "claims:review")
                .build();

        var admin = User.withUsername("admin")
                .password(passwordEncoder.encode("admin"))
                .roles("ADMIN")
                .authorities("underwriting:read", "chat:use", "policies:read",
                        "underwriting:write", "underwriting:approve",
                        "claims:write", "claims:read", "claims:review",
                        "rag:admin", "policies:write", "chat:admin")
                .build();

        return new InMemoryUserDetailsManager(user, underwriter, claims, admin);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 角色层级继承 — ADMIN 自动继承 UNDERWRITER/CLAIMS 权限，UNDERWRITER/CLAIMS 继承 USER 权限
    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy(
            "ADMIN > UNDERWRITER\n" +
            "ADMIN > CLAIMS\n" +
            "UNDERWRITER > USER\n" +
            "CLAIMS > USER"
        );
    }

    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setRoleHierarchy(roleHierarchy);
        return handler;
    }
}

package com.api_loader.api_monitor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import static org.springframework.boot.autoconfigure.security.servlet.PathRequest.toH2Console;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Allow H2 console to work (disable CSRF for H2)
            .csrf(csrf -> csrf.ignoringRequestMatchers(toH2Console())
                              .disable())
            
            // Allow frames for H2 console
            .headers(headers -> headers.frameOptions(frameOptions -> frameOptions.disable()))
            
            // Configure which URLs are protected
            .authorizeHttpRequests(auth -> auth
                // H2 console is public (for development only!)
                .requestMatchers(toH2Console()).permitAll()
                
                // Static resources and public pages
                .requestMatchers("/css/**", "/js/**", "/auth/login", "/auth/signup").permitAll()
                
                // Everything else needs authentication
                .anyRequest().authenticated()
            )
            
            // Use form login for now (will be replaced later)
            .formLogin(form -> form
                .loginPage("/auth/login")
                .defaultSuccessUrl("/", true)
                .permitAll()
            )
            
            .logout(logout -> logout
                .logoutSuccessUrl("/auth/login?logout")
                .permitAll()
            );
        
        return http.build();
    }
    
    // TEMPORARY: Create a test user (will be replaced by your UserService later)
    @Bean
    public UserDetailsService userDetailsService() {
        UserDetails testUser = User.builder()
            .username("test")
            .password("{bcrypt}$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG") // password: test
            .roles("USER")
            .build();
        
        return new InMemoryUserDetailsManager(testUser);
    }
}
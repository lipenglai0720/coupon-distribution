package cn.quanshu;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class ApiSecurity {
  @Bean
  SecurityFilterChain security(
      HttpSecurity http, Accounts accounts, @Value("${quanshu.demo-mode:false}") boolean demo)
      throws Exception {
    return http.csrf(
            AbstractHttpConfigurer::disable) // The API accepts explicit Authorization headers only.
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .headers(
            headers ->
                headers.contentSecurityPolicy(
                    csp ->
                        csp.policyDirectives(
                            "default-src 'self'; object-src 'none'; frame-ancestors 'none';"
                                + " base-uri 'none'; form-action 'self'")))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            rules -> {
              rules.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
              rules.requestMatchers(HttpMethod.GET, "/api/status", "/actuator/health").permitAll();
              rules.requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll();
              if (demo) rules.requestMatchers("/api/**").permitAll();
              else rules.requestMatchers("/api/**").authenticated();
              rules
                  .requestMatchers(
                      HttpMethod.GET, "/", "/index.html", "/app.js", "/app.css", "/favicon.ico")
                  .permitAll();
              rules.anyRequest().denyAll();
            })
        .exceptionHandling(
            errors ->
                errors.authenticationEntryPoint(
                    (request, response, error) -> {
                      response.setHeader("WWW-Authenticate", "Bearer");
                      response.sendError(401, "请先登录");
                    }))
        .addFilterBefore(new BearerFilter(accounts), AnonymousAuthenticationFilter.class)
        .build();
  }

  @Bean
  ApplicationRunner provisionAccounts(
      Accounts accounts, ObjectMapper json, @Value("${quanshu.accounts-file:}") String filename) {
    return args -> {
      if (filename.isBlank()) return;
      Path path = Path.of(filename);
      if (Files.size(path) > 64 * 1024)
        throw new IllegalArgumentException("Account file exceeds 64 KiB");
      try (var input = Files.newInputStream(path)) {
        accounts.provision(
            json.readValue(input, new TypeReference<List<Accounts.SeedAccount>>() {}));
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new IllegalArgumentException("Invalid account file format");
      }
    };
  }

  static String bearer(String header) {
    return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
        ? header.substring(7)
        : null;
  }

  private static class BearerFilter extends OncePerRequestFilter {
    private final Accounts accounts;

    BearerFilter(Accounts accounts) {
      this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      String token = bearer(request.getHeader("Authorization"));
      if (token != null)
        accounts
            .authenticate(token)
            .ifPresent(
                identity ->
                    SecurityContextHolder.getContext()
                        .setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(
                                identity, null, List.of())));
      chain.doFilter(request, response);
    }
  }
}

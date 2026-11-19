package cn.quanshu;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Process boundaries for merchant APIs, coupon APIs and background workers. */
@Component
public class ServiceRoleFilter extends OncePerRequestFilter {
  private final String role, name;

  public ServiceRoleFilter(
      @Value("${quanshu.role:monolith}") String role,
      @Value("${spring.application.name}") String name) {
    if (!Set.of("monolith", "merchant", "template", "coupon", "settlement", "worker")
        .contains(role)) throw new IllegalArgumentException("Unknown service role");
    this.role = role;
    this.name = name;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("X-Quanshu-Service", name);
    response.setHeader("X-Quanshu-Role", role);
    String path = request.getRequestURI();
    if (path.startsWith("/api/") && !allows(path)) {
      response.sendError(404);
      return;
    }
    chain.doFilter(request, response);
  }

  private boolean allows(String path) {
    if (path.equals("/api/status")) return true;
    return switch (role) {
      case "monolith" -> true;
      case "merchant" -> under(path, "/api/tasks");
      case "template" -> under(path, "/api/templates") || path.equals("/api/cache/stats");
      case "coupon" ->
          under(path, "/api/claims") || under(path, "/api/auth") || path.equals("/api/wallet");
      case "settlement" ->
          path.equals("/api/settlement")
              || under(path, "/api/coupons")
              || under(path, "/api/redemptions");
      default -> false;
    };
  }

  private static boolean under(String path, String prefix) {
    return path.equals(prefix) || path.startsWith(prefix + "/");
  }
}

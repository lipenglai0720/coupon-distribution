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
    if (!Set.of("monolith", "merchant", "coupon", "worker").contains(role))
      throw new IllegalArgumentException("Unknown service role");
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
    if (path.startsWith("/api/") && !"monolith".equals(role)) {
      boolean shared = path.equals("/api/status") || path.equals("/api/cache/stats");
      boolean merchant = path.startsWith("/api/templates") || path.startsWith("/api/tasks");
      if (!shared
          && !("merchant".equals(role) && merchant)
          && !("coupon".equals(role) && !merchant)) {
        response.sendError(404);
        return;
      }
    }
    chain.doFilter(request, response);
  }
}

package cn.quanshu.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GatewayApplication {
  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }

  @Bean
  RouteLocator routes(RouteLocatorBuilder builder) {
    return builder
        .routes()
        .route(
            "templates",
            r -> r.path("/api/templates/**", "/api/cache/stats").uri("lb://template-service"))
        .route("merchant", r -> r.path("/api/tasks/**").uri("lb://merchant-service"))
        .route(
            "settlement",
            r ->
                r.path("/api/settlement", "/api/coupons/**", "/api/redemptions/**")
                    .uri("lb://settlement-service"))
        .route("coupon", r -> r.path("/api/**").uri("lb://coupon-service"))
        .route("workspace", r -> r.path("/**").uri("lb://merchant-service"))
        .build();
  }
}

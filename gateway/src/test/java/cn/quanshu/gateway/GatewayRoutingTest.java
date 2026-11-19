package cn.quanshu.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingTest {
  static final HttpServer merchant = upstream("merchant");
  static final HttpServer coupon = upstream("coupon");
  static final HttpServer templates = upstream("templates");
  static final HttpServer settlement = upstream("settlement");
  @Autowired TestRestTemplate http;

  static HttpServer upstream(String role) {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            var body =
                (role
                        + ":"
                        + exchange.getRequestURI()
                        + ":"
                        + exchange.getRequestHeaders().getFirst("X-User-Id"))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
              output.write(body);
            }
          });
      server.start();
      return server;
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void discovery(DynamicPropertyRegistry props) {
    props.add(
        "spring.cloud.discovery.client.simple.instances.template-service[0].uri",
        () -> "http://127.0.0.1:" + templates.getAddress().getPort());
    props.add(
        "spring.cloud.discovery.client.simple.instances.settlement-service[0].uri",
        () -> "http://127.0.0.1:" + settlement.getAddress().getPort());
    props.add(
        "spring.cloud.discovery.client.simple.instances.merchant-service[0].uri",
        () -> "http://127.0.0.1:" + merchant.getAddress().getPort());
    props.add(
        "spring.cloud.discovery.client.simple.instances.coupon-service[0].uri",
        () -> "http://127.0.0.1:" + coupon.getAddress().getPort());
  }

  @AfterAll
  static void stop() {
    merchant.stop(0);
    coupon.stop(0);
    templates.stop(0);
    settlement.stop(0);
  }

  @Test
  void routesBusinessPathsAndPreservesUserAndQuery() {
    var headers = new HttpHeaders();
    headers.set("X-User-Id", "42");
    for (var path : new String[] {"/api/tasks/file", "/api/tasks/abc"})
      assertThat(
              http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                  .getBody())
          .startsWith("merchant:" + path);
    for (var path : new String[] {"/api/templates", "/api/templates/1", "/api/cache/stats"})
      assertThat(
              http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                  .getBody())
          .startsWith("templates:" + path);
    assertThat(http.getForObject("/api/wallet", String.class)).startsWith("coupon:/api/wallet");
    assertThat(http.getForObject("/api/redemptions/1", String.class))
        .startsWith("settlement:/api/redemptions/1");
    assertThat(
            http.exchange(
                    "/api/settlement?merchantId=3",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class)
                .getBody())
        .isEqualTo("settlement:/api/settlement?merchantId=3:42");
    assertThat(http.getForObject("/", String.class)).startsWith("merchant:/");
  }
}

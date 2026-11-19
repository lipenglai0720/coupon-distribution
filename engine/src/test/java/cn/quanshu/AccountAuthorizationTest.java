package cn.quanshu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=${TEST_DB_URL:jdbc:mysql://127.0.0.1:13306/quanshu_test?serverTimezone=UTC}",
      "quanshu.jobs-enabled=false",
      "quanshu.mq-enabled=false",
      "quanshu.redis-enabled=false",
      "quanshu.demo-mode=false"
    })
class AccountAuthorizationTest {
  @Autowired TestRestTemplate http;
  @Autowired Accounts accounts;
  @Autowired DataSourceProperties database;

  @Test
  void authenticatesAcrossRequestsAndSeparatesCustomerAndMerchantData() {
    assertThat(database.getUrl()).contains("/quanshu_test?");
    String suffix = UUID.randomUUID().toString();
    long merchant = 10111, otherMerchant = 10112, customer = 10113;
    String password = UUID.randomUUID().toString();
    String shop = "shop-" + suffix, otherShop = "other-" + suffix, shopper = "buyer-" + suffix;
    accounts.provision(
        List.of(
            new Accounts.SeedAccount(shop, Accounts.Role.MERCHANT, merchant, password),
            new Accounts.SeedAccount(otherShop, Accounts.Role.MERCHANT, otherMerchant, password),
            new Accounts.SeedAccount(shopper, Accounts.Role.CUSTOMER, customer, password)));
    String seller = login(shop, password);
    String other = login(otherShop, password);
    String buyer = login(shopper, password);

    assertStatus(call("/wallet", HttpMethod.GET, null, null), HttpStatus.UNAUTHORIZED);
    assertStatus(
        call(
            "/auth/login",
            HttpMethod.POST,
            null,
            Map.of("username", shopper, "password", "intentionally wrong")),
        HttpStatus.UNAUTHORIZED);
    long now = System.currentTimeMillis();
    var input =
        Map.of(
            "merchantId",
            merchant,
            "name",
            "Account fixture",
            "stock",
            2,
            "thresholdCents",
            100,
            "discountCents",
            20,
            "validFrom",
            now - 1000,
            "validTo",
            now + 60000);
    assertStatus(call("/templates", HttpMethod.POST, buyer, input), HttpStatus.FORBIDDEN);
    assertStatus(call("/templates", HttpMethod.POST, other, input), HttpStatus.FORBIDDEN);
    Number template =
        (Number) successful(call("/templates", HttpMethod.POST, seller, input)).get("id");
    assertStatus(
        call("/templates/" + template + "/end", HttpMethod.POST, other, null),
        HttpStatus.NOT_FOUND);
    String task =
        successful(
                call(
                    "/tasks",
                    HttpMethod.POST,
                    seller,
                    Map.of("templateId", template, "userIds", List.of(customer))))
            .get("id")
            .toString();
    assertStatus(call("/tasks/" + task, HttpMethod.GET, other, null), HttpStatus.NOT_FOUND);
    assertStatus(
        call("/tasks/" + task + "/retry", HttpMethod.POST, other, null), HttpStatus.NOT_FOUND);

    assertThat(recentTasks(seller)).anyMatch(row -> task.equals(row.get("id")));
    assertThat(recentTasks(other)).noneMatch(row -> task.equals(row.get("id")));

    var coupon =
        successful(
            call(
                "/claims",
                HttpMethod.POST,
                buyer,
                Map.of("templateId", template, "requestId", suffix)));
    assertThat(((Number) coupon.get("user_id")).longValue()).isEqualTo(customer);
    String couponId = coupon.get("id").toString();
    successful(
        call(
            "/coupons/" + couponId + "/lock",
            HttpMethod.POST,
            buyer,
            Map.of("orderId", suffix, "merchantId", merchant, "amountCents", 100)));
    assertStatus(
        call("/coupons/" + couponId + "/redeem", HttpMethod.POST, buyer, Map.of("orderId", suffix)),
        HttpStatus.FORBIDDEN);
    assertStatus(
        call("/redemptions/" + suffix + "/redeem", HttpMethod.POST, other, null),
        HttpStatus.NOT_FOUND);
    assertThat(
            successful(call("/redemptions/" + suffix + "/redeem", HttpMethod.POST, seller, null))
                .get("status"))
        .isEqualTo("USED");
    assertThat(
            successful(call("/redemptions/" + suffix + "/refund", HttpMethod.POST, seller, null))
                .get("status"))
        .isEqualTo("AVAILABLE");
    assertStatus(call("/auth/logout", HttpMethod.POST, buyer, null), HttpStatus.NO_CONTENT);
    assertStatus(call("/wallet", HttpMethod.GET, buyer, null), HttpStatus.UNAUTHORIZED);
  }

  private List<Map> recentTasks(String token) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(token);
    var result =
        http.exchange("/api/tasks", HttpMethod.GET, new HttpEntity<>(headers), Map[].class);
    assertStatus(result, HttpStatus.OK);
    return Arrays.asList(Objects.requireNonNull(result.getBody()));
  }

  private String login(String username, String password) {
    return successful(
            call(
                "/auth/login",
                HttpMethod.POST,
                null,
                Map.of("username", username, "password", password)))
        .get("token")
        .toString();
  }

  private ResponseEntity<Map> call(String path, HttpMethod method, String token, Object value) {
    var headers = new HttpHeaders();
    if (token != null) headers.setBearerAuth(token);
    headers.set("X-User-Id", "99999");
    return http.exchange("/api" + path, method, new HttpEntity<>(value, headers), Map.class);
  }

  private Map<?, ?> successful(ResponseEntity<Map> response) {
    assertStatus(response, HttpStatus.OK);
    return Objects.requireNonNull(response.getBody());
  }

  private void assertStatus(ResponseEntity<?> response, HttpStatus expected) {
    assertThat(response.getStatusCode())
        .as("status, response %s", expected.isError() ? response.getBody() : "")
        .isEqualTo(expected);
  }
}

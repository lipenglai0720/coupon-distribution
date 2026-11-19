package cn.quanshu;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** XXL-Job controls maintenance; the fast Outbox pump remains local to the worker. */
@Configuration
@ConditionalOnProperty(
    name = {"quanshu.jobs-enabled", "quanshu.xxl-enabled"},
    havingValue = "true")
public class ExternalJobs {
  private final CouponService coupons;
  private final AsyncClaims claims;
  private final FileDistribution files;
  private final MessageRecovery messages;
  private final Accounts accounts;

  ExternalJobs(
      CouponService coupons,
      AsyncClaims claims,
      FileDistribution files,
      MessageRecovery messages,
      Accounts accounts) {
    this.coupons = coupons;
    this.claims = claims;
    this.files = files;
    this.messages = messages;
    this.accounts = accounts;
  }

  @Bean
  XxlJobSpringExecutor executor(
      @Value("${XXL_ADMIN:http://127.0.0.1:18080/xxl-job-admin}") String admin,
      @Value("${XXL_TOKEN}") String token,
      @Value("${XXL_LOG_PATH:./data/xxl-logs}") String logs) {
    if (token.isBlank()) throw new IllegalArgumentException("XXL_TOKEN must be configured");
    var executor = new XxlJobSpringExecutor();
    executor.setAdminAddresses(admin);
    executor.setAppname("quanshu-worker");
    executor.setIp("127.0.0.1");
    executor.setPort(9999);
    executor.setAccessToken(token);
    executor.setLogPath(logs);
    executor.setLogRetentionDays(7);
    return executor;
  }

  @XxlJob("couponExpiry")
  public void expiry() {
    coupons.expire();
    coupons.expireTemplates();
  }

  @XxlJob("claimRepair")
  public void repair() {
    claims.repair();
    claims.reconcileReservations();
    messages.repair();
    accounts.removeExpiredSessions();
  }

  @XxlJob("fileImport")
  public void fileImport() {
    var task = files.acquire();
    if (task != null) files.process(task);
  }
}

package cn.quanshu;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ApiController {
  private final CouponService service;
  private final JdbcTemplate db;
  private final AsyncClaims async;
  private final FileDistribution files;
  private final boolean mq, redis, demo;
  private final String role;

  public ApiController(
      CouponService service,
      JdbcTemplate db,
      AsyncClaims async,
      FileDistribution files,
      @Value("${quanshu.mq-enabled}") boolean mq,
      @Value("${quanshu.redis-enabled}") boolean redis,
      @Value("${quanshu.demo-mode:false}") boolean demo,
      @Value("${quanshu.role:monolith}") String role) {
    this.service = service;
    this.db = db;
    this.async = async;
    this.files = files;
    this.mq = mq;
    this.redis = redis;
    this.demo = demo;
    this.role = role;
  }

  @GetMapping("/status")
  public Object status() {
    return Map.of(
        "application",
        "优惠券分发与核销系统",
        "demoMode",
        demo,
        "serviceRole",
        role,
        "mqEnabled",
        mq,
        "redisEnabled",
        redis);
  }

  @GetMapping("/templates")
  public Object templates() {
    if (!demo && identity().role() == Accounts.Role.MERCHANT)
      return db.queryForList(
          "SELECT * FROM coupon_template WHERE merchant_id=? ORDER BY id DESC LIMIT 200",
          identity().subjectId());
    return service.templates();
  }

  @GetMapping("/templates/{id}")
  public ResponseEntity<?> template(@PathVariable long id) {
    if (!demo && identity().role() == Accounts.Role.MERCHANT) requireTemplate(id);
    return ResponseEntity.ofNullable(cache.template(id));
  }

  @org.springframework.beans.factory.annotation.Autowired private ReadCaches cache;

  @GetMapping("/cache/stats")
  public Object cacheStats() {
    if (!demo) requireMerchant();
    return cache.stats();
  }

  @PostMapping("/templates")
  public Object create(@RequestBody @Valid CouponService.TemplateInput in) {
    if (!demo && requireMerchant() != in.merchantId())
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不能为其他商家创建模板");
    return Map.of("id", service.create(in));
  }

  @PostMapping("/templates/{id}/end")
  public void end(@PathVariable long id) {
    requireTemplate(id);
    service.end(id);
  }

  private Accounts.Identity identity() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal() instanceof Accounts.Identity identity) return identity;
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  }

  private long requireMerchant() {
    var identity = identity();
    if (identity.role() != Accounts.Role.MERCHANT)
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要商家账号");
    return identity.subjectId();
  }

  private void requireTemplate(long id) {
    if (!demo
        && db.queryForList(
                "SELECT id FROM coupon_template WHERE merchant_id=? AND id=?",
                requireMerchant(),
                id)
            .isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  }

  private void requireTask(String id) {
    if (demo) return;
    long merchant = requireMerchant();
    var task = db.queryForList("SELECT template_id FROM distribution_task WHERE id=?", id);
    if (task.isEmpty()
        || db.queryForList(
                "SELECT id FROM coupon_template WHERE merchant_id=? AND id=?",
                merchant,
                task.get(0).get("template_id"))
            .isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  }

  private long user(Long value) {
    if (!demo) {
      var identity = identity();
      if (identity.role() != Accounts.Role.CUSTOMER)
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要用户账号");
      return identity.subjectId();
    }
    if (value == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入演示用户编号");
    if (value < 1) throw new BusinessException("用户编号必须大于0");
    return value;
  }

  @PostMapping("/claims")
  public Object claim(
      @RequestHeader(name = "X-User-Id", required = false) Long user,
      @RequestBody @Valid CouponService.ClaimInput in) {
    return service.claim(user(user), in);
  }

  @PostMapping("/claims/async")
  public ResponseEntity<?> asyncClaim(
      @RequestHeader(name = "X-User-Id", required = false) Long user,
      @RequestBody @Valid CouponService.ClaimInput in) {
    return ResponseEntity.accepted().body(async.submit(user(user), in));
  }

  @GetMapping("/claims/{request}")
  public Object claimResult(
      @RequestHeader(name = "X-User-Id", required = false) Long user,
      @PathVariable String request) {
    return async.result(user(user), request);
  }

  @GetMapping("/claims")
  public Object claimHistory(@RequestHeader(name = "X-User-Id", required = false) Long user) {
    return db.queryForList(
        "SELECT request_id,template_id,state,coupon_id,error,created_at FROM claim_request WHERE"
            + " user_id=? ORDER BY created_at DESC LIMIT 50",
        user(user));
  }

  @GetMapping("/wallet")
  public Object wallet(@RequestHeader(name = "X-User-Id", required = false) Long user) {
    return service.wallet(user(user));
  }

  @GetMapping("/settlement")
  public Object settle(
      @RequestHeader(name = "X-User-Id", required = false) Long user,
      @RequestParam long merchantId,
      @RequestParam long amountCents) {
    if (amountCents < 1) throw new BusinessException("订单金额必须大于0");
    return service.usable(user(user), merchantId, amountCents);
  }

  @PostMapping("/coupons/{id}/lock")
  public Object lock(
      @RequestHeader(name = "X-User-Id", required = false) Long user,
      @PathVariable String id,
      @RequestBody @Valid CouponService.OrderInput in) {
    return service.lock(user(user), id, in);
  }

  public record Action(
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 100)
          String orderId) {}

  @PostMapping("/coupons/{id}/{action:redeem|unlock|refund}")
  public Object action(
      @RequestHeader(name = "X-User-Id", required = false) Long user,
      @PathVariable String id,
      @PathVariable String action,
      @RequestBody @Valid Action in) {
    if (!demo && !"unlock".equals(action))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "核销和退款由商家确认");
    return service.transition(user(user), id, in.orderId(), action.toUpperCase(Locale.ROOT));
  }

  @GetMapping("/redemptions/{order}")
  public Object order(@PathVariable String order) {
    return redemption(requireMerchant(), order);
  }

  @PostMapping("/redemptions/{order}/{action:redeem|refund}")
  public Object confirmOrder(@PathVariable String order, @PathVariable String action) {
    long merchant = requireMerchant();
    var coupon = redemption(merchant, order);
    return service.transitionForMerchant(
        merchant,
        ((Number) coupon.get("user_id")).longValue(),
        coupon.get("id").toString(),
        order,
        action.toUpperCase(Locale.ROOT));
  }

  private Map<String, Object> redemption(long merchant, String order) {
    var orders =
        db.queryForList("SELECT user_id,coupon_id FROM order_identity WHERE order_id=?", order);
    if (orders.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    var key = orders.get(0);
    return db
        .queryForList(
            "SELECT c.id,c.user_id,c.status,c.order_id,c.lock_until,t.name"
                + " FROM user_coupon c JOIN coupon_template t ON c.template_id=t.id"
                + " WHERE c.user_id=? AND c.id=? AND t.merchant_id=?",
            key.get("user_id"),
            key.get("coupon_id"),
            merchant)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  @PostMapping("/tasks")
  public Object distribute(@RequestBody @Valid CouponService.TaskInput in) {
    requireTemplate(in.templateId());
    return Map.of("id", service.distribute(in));
  }

  @GetMapping("/tasks")
  public Object recentTasks() {
    String select =
        "SELECT d.id,d.template_id,d.parse_state,d.created_at,t.name"
            + " FROM distribution_task d JOIN coupon_template t ON t.id=d.template_id";
    String order = " ORDER BY d.created_at DESC LIMIT 30";
    if (demo) return db.queryForList(select + order);
    return db.queryForList(select + " WHERE t.merchant_id=?" + order, requireMerchant());
  }

  @PostMapping(value = "/tasks/file", consumes = "multipart/form-data")
  public ResponseEntity<?> upload(
      @RequestParam long templateId,
      @RequestParam org.springframework.web.multipart.MultipartFile file)
      throws java.io.IOException {
    requireTemplate(templateId);
    return ResponseEntity.accepted().body(Map.of("id", files.upload(templateId, file)));
  }

  @PostMapping("/tasks/{id}/retry")
  public void retry(@PathVariable String id) {
    requireTask(id);
    files.retry(id);
  }

  @PostMapping(value = "/tasks/import", consumes = "multipart/form-data")
  public Object importCsv(
      @RequestParam long templateId,
      @RequestParam org.springframework.web.multipart.MultipartFile file)
      throws java.io.IOException {
    if (templateId < 1) throw new BusinessException("模板编号不正确");
    requireTemplate(templateId);
    List<Long> users = new ArrayList<>();
    try (var reader =
        new java.io.BufferedReader(
            new java.io.InputStreamReader(
                file.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
      String line;
      int row = 0;
      while ((line = reader.readLine()) != null) {
        row++;
        line = line.replace("\uFEFF", "").trim();
        if (line.isEmpty() || (row == 1 && line.equalsIgnoreCase("user_id"))) continue;
        try {
          long user = Long.parseLong(line);
          if (user < 1) throw new NumberFormatException();
          users.add(user);
        } catch (NumberFormatException e) {
          throw new BusinessException("第 " + row + " 行不是正整数用户编号");
        }
        if (users.size() > 5000) throw new BusinessException("单个任务最多5000个用户");
      }
    }
    if (users.isEmpty()) throw new BusinessException("文件没有有效用户");
    return Map.of("id", service.distribute(new CouponService.TaskInput(templateId, users)));
  }

  @GetMapping("/tasks/{id}")
  public Object task(@PathVariable String id) {
    requireTask(id);
    return service.task(id);
  }

  @RestControllerAdvice
  public static class Errors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> http(ResponseStatusException e) {
      return ResponseEntity.status(e.getStatusCode())
          .body(Map.of("message", Objects.requireNonNullElse(e.getReason(), "无法完成请求")));
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<?> business(BusinessException e) {
      return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict() {
      return ResponseEntity.status(409).body(Map.of("message", "请求与已有数据冲突，请查询当前状态"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {
      return ResponseEntity.badRequest()
          .body(
              Map.of(
                  "message",
                  e.getBindingResult().getFieldErrors().stream()
                      .map(x -> x.getField() + " " + x.getDefaultMessage())
                      .toList()));
    }
  }
}

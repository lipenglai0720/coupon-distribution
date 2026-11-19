package cn.quanshu;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration
@ConditionalOnProperty(name = "quanshu.mq-enabled", havingValue = "true")
class RabbitTopology {
  @Bean
  Declarables topology() {
    var exchange = new DirectExchange("quanshu.events", true, false);
    var deadExchange = new DirectExchange("quanshu.dead", true, false);
    var queue =
        QueueBuilder.durable("quanshu.worker")
            .deadLetterExchange("quanshu.dead")
            .deadLetterRoutingKey("failed")
            .build();
    var dead = QueueBuilder.durable("quanshu.failed").build();
    var delayedExchange = new DirectExchange("quanshu.delay", true, false);
    var declarations =
        new ArrayList<Declarable>(
            List.of(
                exchange,
                deadExchange,
                queue,
                dead,
                delayedExchange,
                BindingBuilder.bind(queue).to(exchange).with("work"),
                BindingBuilder.bind(dead).to(deadExchange).with("failed")));
    for (int ttl : new int[] {1000, 10000, 60000, 600000, 3600000}) {
      var delayed =
          QueueBuilder.durable("quanshu.delay." + ttl)
              .ttl(ttl)
              .deadLetterExchange("quanshu.events")
              .deadLetterRoutingKey("work")
              .build();
      declarations.add(delayed);
      declarations.add(BindingBuilder.bind(delayed).to(delayedExchange).with("wait." + ttl));
    }
    return new Declarables(declarations);
  }
}

@Component
@ConditionalOnProperty(name = "quanshu.jobs-enabled", havingValue = "true", matchIfMissing = true)
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
    "!${quanshu.xxl-enabled:false}")
class ExpiryWorker {
  private final CouponService service;
  private final Accounts accounts;

  ExpiryWorker(CouponService service, Accounts accounts) {
    this.service = service;
    this.accounts = accounts;
  }

  @Scheduled(fixedDelay = 10000)
  public void tick() {
    service.expire();
    service.expireTemplates();
    accounts.removeExpiredSessions();
  }
}

@Component
@ConditionalOnProperty(
    name = {"quanshu.mq-enabled", "quanshu.jobs-enabled"},
    havingValue = "true")
class OutboxWorker {
  private final JdbcTemplate db;
  private final RabbitTemplate rabbit;
  private final ObjectMapper json;

  OutboxWorker(JdbcTemplate db, RabbitTemplate rabbit, ObjectMapper json) {
    this.db = db;
    this.rabbit = rabbit;
    this.json = json;
  }

  @Scheduled(fixedDelay = 200)
  public void send() {
    for (var row :
        db.queryForList(
            "SELECT * FROM outbox WHERE state='PENDING' AND next_attempt<=? ORDER BY created_at"
                + " LIMIT 200",
            System.currentTimeMillis())) {
      String id = row.get("id").toString();
      try {
        var properties = new MessageProperties();
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(id);
        properties.setHeader("kind", row.get("kind").toString());
        long deliveryAt = json.readTree(row.get("payload").toString()).path("deliveryAt").asLong(0);
        long delay = deliveryAt - System.currentTimeMillis();
        int bucket = 1000;
        for (int candidate : new int[] {1000, 10000, 60000, 600000, 3600000})
          if (delay >= candidate) bucket = candidate;
        var correlation = new CorrelationData(id);
        rabbit.send(
            delay > 0 ? "quanshu.delay" : "quanshu.events",
            delay > 0 ? "wait." + bucket : "work",
            new Message(
                row.get("payload").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                properties),
            correlation);
        var result = correlation.getFuture().get(5, TimeUnit.SECONDS);
        if (!result.isAck() || correlation.getReturned() != null)
          throw new IllegalStateException("Broker 拒绝或消息未路由");
        db.update(
            "UPDATE outbox SET state='SENT',last_error=NULL,next_attempt=? WHERE id=? AND"
                + " state='PENDING'",
            Math.max(System.currentTimeMillis(), deliveryAt) + 60000,
            id);
      } catch (Exception e) {
        int attempts = ((Number) row.get("attempts")).intValue() + 1;
        db.update(
            "UPDATE outbox SET state=?,attempts=?,next_attempt=?,last_error=? WHERE id=? AND"
                + " state='PENDING'",
            attempts >= 10 ? "FAILED" : "PENDING",
            attempts,
            System.currentTimeMillis() + Math.min(300000, 1000L * (1L << Math.min(attempts, 8))),
            e.getClass().getSimpleName(),
            id);
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }
}

@Component
@ConditionalOnProperty(
    name = {"quanshu.mq-enabled", "quanshu.jobs-enabled"},
    havingValue = "true")
class RabbitConsumer {
  private final CouponService service;
  private final ObjectMapper json;
  private final AsyncClaims async;
  private final JdbcTemplate db;

  RabbitConsumer(CouponService service, ObjectMapper json, AsyncClaims async, JdbcTemplate db) {
    this.service = service;
    this.json = json;
    this.async = async;
    this.db = db;
  }

  @RabbitListener(queues = "quanshu.worker", ackMode = "MANUAL")
  public void handle(Message message, Channel channel) throws java.io.IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    try {
      var payload = json.readValue(message.getBody(), new TypeReference<Map<String, Object>>() {});
      String kind = message.getMessageProperties().getHeader("kind");
      if ("ASYNC_CLAIM".equals(kind))
        async.consume(
            message.getMessageProperties().getMessageId(), payload.get("requestId").toString());
      else service.consume(message.getMessageProperties().getMessageId(), kind, payload);
      db.update(
          "UPDATE outbox SET state='CONSUMED',last_error=NULL WHERE id=?",
          message.getMessageProperties().getMessageId());
      channel.basicAck(tag, false); // Transactional service proxy has committed before returning.
    } catch (Exception e) {
      channel.basicNack(tag, false, false);
    }
  }
}

@Component
@ConditionalOnProperty(
    name = {"quanshu.redis-enabled", "quanshu.jobs-enabled"},
    havingValue = "true")
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
    "!${quanshu.xxl-enabled:false}")
class ClaimRepairWorker {
  private final AsyncClaims async;
  private final MessageRecovery messages;

  ClaimRepairWorker(AsyncClaims async, MessageRecovery messages) {
    this.async = async;
    this.messages = messages;
  }

  @Scheduled(fixedDelay = 5000)
  public void tick() {
    async.repair();
    async.reconcileReservations();
    messages.repair();
  }
}

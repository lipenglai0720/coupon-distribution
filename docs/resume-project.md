# 简历项目参考

**优惠券分发与核销系统** · 个人后端项目

Java 17、Spring Boot 3、Nacos、Gateway、MySQL、Redis、Redisson、RabbitMQ、EasyExcel、ShardingSphere、XXL-Job

- 通过 Nacos 与 Gateway 运行商家、模板、用户券、结算及后台分发进程，覆盖建券、领券、批量发券、核销与退款。
- 基于 **Redis Lua** 原子校验限领与预扣库存，用 **Outbox 与 RabbitMQ** 异步发券，结合 MySQL 条件扣减、唯一约束及 Inbox 防止超发和重复落库。
- 使用 **EasyExcel** 流式读取 XLSX，**Redis Set** 暂存去重，RabbitMQ 分批投递，结合检查点、任务租约及 **XXL-Job** 恢复失败任务。
- 通过 **Redis ZSet + Pipeline** 读取券包，使用 CompletableFuture 分块计算，以 Redisson 锁和数据库状态条件控制锁券、核销及退款，用 RabbitMQ 延迟事件处理过期。
- 通过 **ShardingSphere 自定义 Hash**，模板按 shop_number、用户券按 user_id 在同一 MySQL 实例内分库分表。建券后预热缓存，结合 BloomFilter、空值缓存及分布式锁双检处理穿透与击穿。

面试时可展示 [架构图](architecture.md)、[功能验收记录](progress.md) 和 [接口用例](../requests.http)。

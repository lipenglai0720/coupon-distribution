# 简历项目参考

**优惠券分发与核销系统** · 个人后端项目

Java 17、Spring Boot、Spring Security、Nacos、Gateway、MySQL、Redis、Redisson、RabbitMQ、ShardingSphere、XXL-Job

- 用 Redis Lua 原子预占库存和校验限领，结合 MySQL 条件扣减、幂等键与事务确定发券结果。1000 个请求争抢 100 张券，实发 100 张，库存和落库结果一致。
- 用 Outbox、发布确认和 Inbox 管理异步发券，验证 Broker 中断恢复及重复投递。支持 CSV/XLSX 分批解析和断点恢复，在真实 RabbitMQ 环境完成 5000 人 Excel 发券，全部成功。
- 实现商家与用户帐号隔离、短期可撤销会话，以及结算、锁券、商家核销和退款。使用 Redis ZSet 与 Pipeline 加速钱包读取，通过缓存完成标记校验解决并发续期时的漏券。
- 通过 Nacos 与 Gateway 拆分商家接口、用户券接口和后台任务。用 ShardingSphere 将模板和用户券分别路由至四张物理表，通过统一 ID 和数据库约束保留同库事务一致性。

面试时可展示 [架构图](architecture.md)、[测试记录](progress.md) 和 [接口用例](../requests.http)。引用吞吐量时附上部署方式、登录模式和缓存口径。百万行验证覆盖 CSV 解析与检查点，完整发券实测规模为 5000 人。

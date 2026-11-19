# 业务规格与实现范围

固定规则：每用户每模板限领一张，单订单单券，满减券，金额为整数分，模拟订单与全额退款。

同步领取返回成功表示MySQL券已提交；异步领取202只表示请求已受理，调用查询接口取得PENDING/SUCCESS/FAILED结果。Redis Lua控制预占与限领，数据库持久化意图、库存条件更新和唯一约束是最终一致性边界。

模板ACTIVE/ENDED，用户券AVAILABLE/LOCKED/USED/EXPIRED。有效期是实时权威约束；扫描与延迟消息物化状态。锁定最长15分钟；旧订单不能操作新锁。退款不回补发行库存，结束活动只停止新领取。

M0～M2：IDEA Maven工程、Flyway、隔离配置；模板、库存、用户券、结算和核销状态机。

M3：原生RabbitMQ、Lua预占、持久化请求与Outbox、Confirm/Returns、Inbox、提交后ACK、修复与死信。已发送但未消费确认的记录在应到时间60秒后补投，有限重试后保留FAILED。

M4：JSON最多5000用户；CSV/XLSX异步流式读取最多100万行，500行checkpoint与租约、无效行、去重、进度及解析重试。实际已验收5000人，百万行上限不等于已完成百万行压测。导入使用POI SAX。

M5：Redisson锁、BloomFilter、负缓存、双检、ZSet/Pipeline钱包缓存；小钱包直接计算，较大钱包通过有界CompletableFuture任务分块计算。

M6：Nacos与Gateway，商家/用户券/后台分发进程；模板按merchant_id、用户券按user_id分别Hash到4表，统一ID和全局请求/订单约束。同库事务，不声称跨库分片。XXL-Job执行过期、修复与导入；Rabbit固定TTL分桶延迟事件。

M7：真实并发、消息故障、文件发券、Chrome交互、分片落库、调度回调及JMeter验证。最新实测结果见progress.md与evidence。

本项目已接入账号登录和商家授权。仍需外部支付集成、多节点高可用、跨库事务以及长期稳定性验证。

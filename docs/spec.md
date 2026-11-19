# 业务规格与实现范围

固定规则：每用户每模板限领一张，单订单单券，满减券，金额为整数分，模拟订单与全额退款。

同步领取返回成功表示MySQL券已提交；异步领取202只表示请求已受理，调用查询接口取得PENDING/SUCCESS/FAILED结果。Redis Lua控制预占与限领，数据库持久化意图、库存条件更新和唯一约束是最终一致性边界。

模板ACTIVE/ENDED，用户券AVAILABLE/LOCKED/USED/EXPIRED。有效期是实时权威约束；扫描与延迟消息物化状态。锁定最长15分钟；旧订单不能操作新锁。退款不回补发行库存，结束活动只停止新领取。

M0～M2：IDEA Maven工程、Flyway、隔离配置；模板、库存、用户券、结算和核销状态机。

M3：原生RabbitMQ、Lua预占、持久化请求与Outbox、Confirm/Returns、Inbox、提交后ACK、修复与死信。已发送但未消费确认的记录在应到时间60秒后补投，有限重试后保留FAILED。

M4：JSON 最多 5000 用户；CSV/XLSX 异步流式读取最多 100 万行。EasyExcel 解析 XLSX，Redis Set 对每批去重。每 500 行提交检查点，每条 RabbitMQ 批量事件最多携带 100 名收件人。任务保留租约、无效行和投递进度。

M5：Redisson锁、BloomFilter、负缓存、双检、ZSet/Pipeline钱包缓存；小钱包直接计算，较大钱包通过有界CompletableFuture任务分块计算。

M6：Nacos 与 Gateway 路由至商家、模板、用户券及结算进程，后台分发进程消费消息。模板按商家编号、用户券按 user_id 自定义 Hash，各四张表落在同一 MySQL 实例的两个分片库。XXL-Job 执行过期、修复与导入，RabbitMQ 固定 TTL 分桶处理延迟事件。

M7：验证并发库存一致性、消息故障恢复、文件发券、Chrome 交互、分片落库及调度回调。验收结果见 progress.md 与 evidence。

本项目已接入账号登录和商家授权。当前部署使用单实例 MySQL 本地事务。外部支付集成、多节点高可用与长期稳定性验证仍待完成。

# 开发进度与验收证据

## 2026-11-19 展示版

- IDEA Maven verify 通过 32 项测试，覆盖账号登录、商家间数据隔离、用户越权核销拦截、退出失效和缓存索引丢失回源。[构建证据](evidence/verify-auth.json)
- 浏览器验收涵盖商家建券、真实异步领取、用户锁券、商家按订单号核销和退款、CSV 名单去重发券以及退出登录。[浏览器记录](evidence/browser-auth-e2e.json)
- 验证 CSV 解析的 100 万条记录上限、末尾检查点和超限拒绝行为。
- 验证钱包索引缺失时回到 MySQL 查询，避免把未完成的缓存快照当成空券包。

## 2026-11-16 基线

2026-11-16：应用与中间件原生运行于Ubuntu26.04.1用户目录。无Docker、无sudo依赖；IDEA内置Maven。代码结构为engine与gateway模块，商家、用户券和后台分发分进程运行。

### 功能验收

| 检查 | 结果 | 证据 |
|---|---|---|
| Maven verify | 29项，0失败、0错误、0跳过 | evidence/maven-distributed-verify.json |
| Nacos与Gateway | 4个健康注册；商家/券接口实际分流 | evidence/distributed-e2e.json |
| 物理Hash分表 | 模板按商家、券按用户，各4表；直接查询验证 | 集成测试及distributed-e2e.json |
| XXL-Job | 3个Handler均有调度成功及业务回调成功记录 | distributed-e2e.json |
| 延迟过期 | Rabbit固定TTL队列到期事件进入Inbox，券状态EXPIRED | distributed-e2e.json |
| 并发领取 | 库存扣减与成功发券数一致，库存耗尽后的请求被拒绝 | evidence/async-distributed-e2e.json |
| JSON批量发券 | 5000成功/0失败 | evidence/rabbit-distributed-5000.json |
| XLSX文件发券 | 5000成功/0失败，检查点包含表头 | evidence/file-distributed-5000.json |
| Broker中断与重投 | 中断PENDING、恢复成功；同消息5次不重复发券；异常消息进DLQ | evidence/mq-fault-distributed-e2e.json |
| Publisher Returns | 实际解绑记录失败，恢复绑定后自动完成 | evidence/returns-distributed-e2e.json |
| Chrome页面 | 创建→异步领券→锁券→核销→退款→CSV完成→结束活动；无脚本异常；390px无横向溢出 | evidence/browser-distributed-e2e.json |
| 库存最终对账 | 14模板、20311张物理券；库存扣减20311；差异0；负库存0 | evidence/inventory-audit.json |
| 桌面启动 | Windows PowerShell启动辅助脚本-NoBrowser实际成功 | .local/start-development.log；桌面优惠券分发与核销系统快捷方式 |

## 已实现范围

M0～M6核心业务与架构链路均有代码与实际运行证据：模板/领券/状态机/结算/批发，Lua、Outbox/Inbox、补偿，SAX流式文件、checkpoint与租约，Redisson、BloomFilter、ZSet/Pipeline、CompletableFuture，Nacos/Gateway、Hash分表、XXL调度及Rabbit延迟消息。

运行入口为 http://localhost:8080 。在 IDEA 打开根 pom.xml。集成测试报告由 Maven 写入 engine 和 gateway 模块的 target/surefire-reports。

## 必须保留的边界

- 同库分表，不是跨库分片；业务进程共享代码、数据库和本机文件目录。
- POI SAX承担流式解析，RabbitMQ替代RocketMQ；不要将实际未使用的库写入技术栈。
- 项目已接入账号会话和商家授权。真实支付仍待集成。
- 百万行验证覆盖 CSV 解析、检查点与超限处理。高可用、自动灾备恢复和生产监控仍待验收。
- 进程不是系统服务；VM重启后需要启动辅助脚本。当前VM内存偏小，长期运行需规划RAM、磁盘与日志轮转。

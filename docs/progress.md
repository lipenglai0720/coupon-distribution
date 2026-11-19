# 开发进度与验收证据
## 2026-11-19 展示版

- IDEA Maven verify 通过 32 项测试，覆盖账号登录、商家间数据隔离、用户越权核销拦截、退出失效和缓存索引丢失回源。[构建证据](evidence/verify-auth.json)
- 浏览器验收涵盖商家建券、真实异步领取、用户锁券、商家按订单号核销和退款、CSV 名单去重发券以及退出登录。[浏览器记录](evidence/browser-auth-e2e.json)
- 在最多 128 MiB 的测试 JVM 堆中读取 100 万条 CSV 用户记录，验证末尾检查点和超限拒绝。该验证只覆盖读取阶段。
- 登录模式下经网关执行 8 万次热缓存结算查询，32 线程，0 错误，约 2680 QPS，P95 12 ms，P99 15 ms。每个请求校验到期会话，响应断言要求包含有效的 2000 分优惠券。[压测口径](evidence/settlement-auth-80000.json)
- 第一次登录模式压测在钱包索引续期时发现 7 次业务断言失败。修复在快照填充时加互斥，并通过完成标记校验条目数。不完整的索引回 MySQL 查询，修复后用同一规模复测。

复现脚本位于 scripts/qa。Windows 可用 settlement-auth.ps1 指定 JMeter 和本地账号文件，脚本会建立测试券、预热结算、保存报告并注销会话。

## 2026-11-16 基线


2026-11-16：应用与中间件原生运行于Ubuntu26.04.1用户目录。无Docker、无sudo依赖；IDEA内置Maven。代码结构为engine与gateway模块，商家、用户券和后台分发分进程运行。

## 本次最终验证

| 检查 | 结果 | 证据 |
|---|---|---|
| Maven verify | 29项，0失败、0错误、0跳过 | evidence/maven-distributed-verify.json |
| Nacos与Gateway | 4个健康注册；商家/券接口实际分流 | evidence/distributed-e2e.json |
| 物理Hash分表 | 模板按商家、券按用户，各4表；直接查询验证 | 集成测试及distributed-e2e.json |
| XXL-Job | 3个Handler均有调度成功及业务回调成功记录 | distributed-e2e.json |
| 延迟过期 | Rabbit固定TTL队列到期事件进入Inbox，券状态EXPIRED | distributed-e2e.json |
| 并发领取 | 1000请求抢100张，100成功/900库存不足，3.193秒 | evidence/async-distributed-e2e.json |
| JSON批量发券 | 5000成功/0失败，37.820秒 | evidence/rabbit-distributed-5000.json |
| XLSX文件发券 | 5000成功/0失败，约64ms受理，49.052秒完成，checkpoint5001含表头 | evidence/file-distributed-5000.json |
| Broker中断与重投 | 中断PENDING、恢复成功；同消息5次不重复发券；异常消息进DLQ | evidence/mq-fault-distributed-e2e.json |
| Publisher Returns | 实际解绑记录失败，恢复绑定后自动完成 | evidence/returns-distributed-e2e.json |
| Chrome页面 | 创建→异步领券→锁券→核销→退款→CSV完成→结束活动；无脚本异常；390px无横向溢出 | evidence/browser-distributed-e2e.json |
| 库存最终对账 | 14模板、20311张物理券；库存扣减20311；差异0；负库存0 | evidence/inventory-audit.json |
| JMeter完整部署 | 32线程×2500=80000；0错误；约3682QPS，P95 9ms/P99 12ms | evidence/settlement-distributed-80000.json |
| 桌面启动 | Windows PowerShell启动辅助脚本-NoBrowser实际成功 | .local/start-development.log；桌面优惠券分发与核销系统快捷方式 |

JMeter采用热Redis钱包缓存、小钱包、Windows驱动经SSH与Gateway访问coupon-service；响应包含优惠2000分的业务断言。VM为8vCPU/3350MiB，多个Java进程共享内存并使用交换空间。这些数据不能代表冷缓存、大钱包、写入吞吐或生产容量。

历史单体阶段22项测试、15.85秒JSON5000、17.396秒XLSX5000、结算约5500QPS/P99 10ms仍保留在原evidence文件。它们属于较早部署，不能与当前分片/网关结果混用。

## 已实现范围

M0～M6核心业务与架构链路均有代码与实际运行证据：模板/领券/状态机/结算/批发，Lua、Outbox/Inbox、补偿，SAX流式文件、checkpoint与租约，Redisson、BloomFilter、ZSet/Pipeline、CompletableFuture，Nacos/Gateway、Hash分表、XXL调度及Rabbit延迟消息。

运行入口 http://localhost:8080 。IDEA打开根pom.xml。桌面优惠券分发与核销系统快捷方式检查服务及转发后打开页面。原始测试报告在engine/target/surefire-reports和gateway/target/surefire-reports；最新完整日志.local/verify-release.log。页面截图.local/desktop.png、.local/mobile.png。当前压测原始JTL与HTML在.local/settlement-distributed-*。

## 必须保留的边界

- 同库分表，不是跨库分片；业务进程共享代码、数据库和本机文件目录。
- POI SAX承担流式解析，RabbitMQ替代RocketMQ；不要将实际未使用的库写入技术栈。
- 后续版本加入账号会话和商家授权。历史压测采用隔离环境的演示身份，真实支付集成仍未接入。
- 百万行仅为解析上限，未做百万行完整发券压力测试。高可用、长时间稳定性、自动灾备恢复和生产监控未验收。
- 进程不是系统服务；VM重启后需要启动辅助脚本。当前VM内存偏小，长期运行需规划RAM、磁盘与日志轮转。
- 不宣称复现截图7600QPS/P99 2ms等数字，只引用项目自己的测量与测试环境。

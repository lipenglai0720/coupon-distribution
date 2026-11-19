# 项目约定

Java 17、IDEA 内置 Maven、MySQL 8、RabbitMQ。engine 与 gateway 为 Maven 模块；业务角色部署见 docs/architecture.md。
当前运行于 Ubuntu 用户目录，部署、隧道与测试隔离说明见 docs/ubuntu-deployment.md。
构建优先调用 IDEA 安装中的 `plugins/maven-plugin/lib/maven3/bin/mvn.cmd`，具体环境见 docs/environment.md。
需求与实现边界见 docs/spec.md；完成情况和实测证据见 docs/progress.md。
修改领券、状态流转、消息处理时，验证真实 MySQL 的事务、并发和幂等行为。测试使用 quanshu_test，禁止指向开发库运行清理逻辑。
Redis集成测试专用数据库15，测试中可清空；开发服务使用数据库0。
默认 Maven 配置启用 discovery、sharding 及 sharded Spring profile。迁移时先停止业务写入并备份主库和两个分片库。分片键、存储标记与本地事务见 docs/ubuntu-deployment.md。
RabbitMQ 确认与 MySQL 提交是两个阶段。消费者在业务事务提交返回后 ACK；生产者使用 Outbox、Confirm 和 Returns。
凭据保存在被 Git 忽略的本地配置或环境变量中。测试未执行时标注未验证。

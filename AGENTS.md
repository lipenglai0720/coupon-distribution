# 项目约定

Java 17、IDEA 内置 Maven、MySQL 8、RabbitMQ。engine与gateway为Maven模块；商家、用户券及后台任务分进程运行，共享业务数据库与导入文件目录。
当前运行于 Ubuntu 用户目录，部署、隧道与测试隔离说明见 docs/ubuntu-deployment.md。
构建优先调用 IDEA 安装中的 `plugins/maven-plugin/lib/maven3/bin/mvn.cmd`，具体环境见 docs/environment.md。
需求与实现边界见 docs/spec.md；完成情况和实测证据见 docs/progress.md。
修改领券、状态流转、消息处理时，验证真实 MySQL 的事务、并发和幂等行为。测试使用 quanshu_test，禁止指向开发库运行清理逻辑。
Redis集成测试专用数据库15，测试中可清空；开发服务使用数据库0。
默认Maven配置启用discovery、sharding及sharded Spring profile；模板按merchant_id、用户券按user_id分为4表。全局请求/订单约束表和模板ID分配不能替换成分片内唯一索引/自增。
storage_mode切换后禁止直接读取旧单表；迁移必须停业务进程并备份。当前同库分表以保留本地事务，不得描述成跨库分片。
RabbitMQ 确认与 MySQL 提交是两个阶段。消费者在业务事务提交返回后 ACK；生产者使用 Outbox、Confirm 和 Returns。
凭据保存在被 Git 忽略的本地配置或环境变量中。测试未执行时标注未验证。

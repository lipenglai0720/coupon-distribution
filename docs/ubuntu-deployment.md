# Ubuntu用户目录部署

验证环境为 Ubuntu26.04.1，8 vCPU、3350 MiB RAM、约3.8GiB交换空间。原生软件解压到 `~/quanshu-runtime`；无需sudo，不使用Docker。SSH 使用部署者自己的身份文件。

## 服务与入口

| 服务 | 版本/角色 | 虚拟机端口 | Windows转发 |
|---|---|---|---|
| MySQL | 8.4.13 | 3306 | 13306 |
| Redis | 8.0.5 | 6379 | 16379 |
| RabbitMQ | 4.4.0 / Erlang27.3.4.6 | 5672 | 15673 |
| RabbitMQ管理 | 项目vhost quanshu | 15672 | 15672 |
| Nacos | 3.0.3 / 服务注册 | 8848，gRPC9848 | 由VM内客户端访问 |
| Nacos控制台 | 已启用控制台鉴权 | 18848 | 18848 |
| Gateway | 统一入口 | 8080 | 8080 |
| 商家服务 | merchant-service | 8083 | 经网关 |
| 模板服务 | template-service | 8085 | 经网关 |
| 用户券服务 | coupon-service | 8082 | 经网关 |
| 结算服务 | settlement-service | 8086 | 经网关 |
| 分发服务 | distribution-service | 8084 | 健康端点，业务API关闭 |
| XXL-Job管理 | 3.1.1 | 18080 | 18080 |
| XXL-Job执行器 | quanshu-worker | 9999 | VM内部调度 |

HTTP入口绑定回环地址。部分中间件内部协议监听通配地址，未开放公网访问。Nacos服务端客户端API采用开发环境配置，控制台启用鉴权。不要将该环境直接暴露公网。

凭据在VM `config/secrets.env`、`config/mysql-client.cnf` 以及Windows被Git忽略的 `.local/vm.properties` 和 `application-local.properties`，不写入仓库。XXL管理用户为admin，随机密码由脚本保存在secrets.env；Nacos控制台首次使用需初始化管理员，与应用注册API相互独立。

## 启动与升级

Windows脚本 `scripts/start-development.ps1` 会检查原生服务、Nacos和XXL管理中心，必要时启动业务角色，建立缺少的SSH转发并打开网页。前提是Ubuntu虚拟机已经开机，当前SSH密钥可用。连接或启动失败会弹窗并将日志写入 `.local/start-development.log`。

VM脚本执行顺序：start-user-services.sh → start-nacos.sh → start-xxl-admin.sh → start-platform.sh。前三个脚本可重复调用；start-platform会停止并重启本项目业务进程，备份业务库，执行迁移和分片初始化，再等待健康检查。

构建使用IDEA Maven `verify`。新引擎JAR上传为 `app/quanshu-engine.next.jar`，网关为 `app/quanshu-gateway.next.jar`，随后执行start-platform.sh切换。不要覆盖正在运行的JAR。启动脚本只按记录PID及应用路径停止本项目进程。原start-app.sh保留为单表历史入口，当前分片库禁止用它回退。

重启VM后需要重新运行用户进程及Windows转发；尚未配置系统级自动启动。start-user-services脚本依赖已经解压的Ubuntu包，不是空机器安装器。Erlang ROOTDIR与Java /etc符号链接已重定位到用户前缀。

## 分片与数据

模板按商家编号 Hash 至 `coupon_template_0`～`coupon_template_3`，物理分片键为 `shop_number`，API 继续使用 `merchantId`。用户券按 `user_id` Hash 至 `user_coupon_0`～`user_coupon_3`。算法为 `floorMod(Long.hashCode(id), 4)`。编号 0、1 的物理表位于 `quanshu_s0`，编号 2、3 位于 `quanshu_s1`。模板 ID 单独分配，请求和订单使用主库全局约束表。

主库通过可更新视图访问两个分片库的物理表，所有业务写入使用同一 MySQL 实例的本地事务。应用账号需要主库与两个分片库的权限。两个分片表的关联查询可能遍历多个节点，业务查询优先带分片键。

迁移必须先停止业务写入。`start-platform.sh` 将主库和已有分片库备份到 VM 的 `backups`，然后在 MySQL 命名锁下准备存储。旧单表数据先迁至四个桶，再用原子改名移至两个分片库，创建可更新视图后写入 `TWO_SCHEMA_HASH_4`。初始化可重试。旧单表保留迁移时快照，存储标记切换后应通过分片模式访问业务数据。

## 测试与调度

在运行目录的 `config/accounts.json` 放置账号清单，格式参照 [示例账号文件](../examples/accounts.example.json)。文件权限应设为 600。商家进程启动时将新账号写入数据库，已有账号不覆盖。日常运行可以将已使用的账号文件移出运行目录。

引擎默认要求短期 Bearer 会话，网关转发 Authorization，所有业务进程查询同一会话库。只在隔离的测试实例开启 `DEMO_MODE=true`。通过公网反向代理运行时应先配置 HTTPS，并为登录接口设置请求频率限制。

Windows 启动脚本支持 `-VmHost`、`-VmUser` 和 `-IdentityFile`，也可分别配置 `COUPON_VM_HOST`、`COUPON_VM_USER` 和 `COUPON_VM_IDENTITY`。原生 VM 验收脚本通过 `COUPON_VM_SSH_TARGET` 指定 SSH 目标。

Maven verify 默认运行分片模式。测试使用 `quanshu_test`、`quanshu_test_s0`、`quanshu_test_s1` 和 Redis 数据库 15。开发实例使用 `quanshu` 系列库和 Redis 数据库 0。

需要全新测试模式时，`reset-test-database.sh` 仅重建三个固定名称的测试库。测试期间同一组测试库应由一套验证任务独占。

XXL调度：couponExpiry每10秒、claimRepair每5秒、fileImport每2秒。Outbox发送泵由worker每200ms运行；业务API角色不运行调度或消息消费者。消费成功后Outbox标记CONSUMED；已发送但超过应到时间60秒仍未确认的记录会补投，最多10轮后FAILED供排查。

延迟事件使用1秒/10秒/1分钟/10分钟/1小时固定TTL队列，再由死信路由回工作队列。提前唤醒的事件重新安排剩余延时；所有处理都检查实际截止时间和订单，XXL扫描兜底。死信队列保留无法处理的消息，不自动无限循环未知消息。

## 证据与资源

完整集成测试报告在 engine/target/surefire-reports 和 gateway/target/surefire-reports。验收摘要见 docs/evidence。

原生包、JAR、日志和备份会占用磁盘空间。长期运行时请为日志和备份配置保留期限。

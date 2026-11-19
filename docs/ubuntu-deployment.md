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
| 用户券服务 | coupon-service | 8082 | 经网关 |
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

模板按merchant_id（业务中的商家编号）Hash至coupon_template_0～3，用户券按user_id Hash至user_coupon_0～3，算法为Long.hashCode后floorMod4。模板ID来自单独的id_allocator；请求和订单有独立的全局约束表。

这是同库分表。所有业务写入仍使用同一物理MySQL数据源，以保留模板库存、用户券、Outbox的本地原子事务。没有跨库XA能力。两张表的关联查询需要散播；带分片键查询优先。旧单表是迁移时快照，切换后不能当作实时数据源。

迁移必须先停止所有业务写入。start-platform先备份到VM backups，再用MySQL命名锁串行化一次性拷贝，并在同一事务设置storage_mode。普通单表模式检测到切换标记会拒绝启动。恢复旧备份意味着丢弃备份之后的数据，应另行设计恢复步骤，不能只改标记。

## 测试与调度

在运行目录的 `config/accounts.json` 放置账号清单，格式参照 [示例账号文件](../examples/accounts.example.json)。文件权限应设为 600。商家进程启动时将新账号写入数据库，已有账号不覆盖。日常运行可以将已使用的账号文件移出运行目录。

引擎默认要求短期 Bearer 会话，网关转发 Authorization，所有业务进程查询同一会话库。只在隔离的测试实例开启 `DEMO_MODE=true`。通过公网反向代理运行时应先配置 HTTPS，并为登录接口设置请求频率限制。

Windows 启动脚本支持 `-VmHost`、`-VmUser` 和 `-IdentityFile`，也可分别配置 `COUPON_VM_HOST`、`COUPON_VM_USER` 和 `COUPON_VM_IDENTITY`。原生 VM 验收脚本通过 `COUPON_VM_SSH_TARGET` 指定 SSH 目标。

Maven verify默认运行分片模式；本机私有连接已配置。测试库固定quanshu_test，Redis数据库15；测试会删除这些测试记录。开发库quanshu与Redis数据库0不做测试清理。

需要全新测试模式时，reset-test-database.sh只重建硬编码的quanshu_test，不接受数据库参数。测试期间不要并行运行另一套清理相同测试库的任务。

XXL调度：couponExpiry每10秒、claimRepair每5秒、fileImport每2秒。Outbox发送泵由worker每200ms运行；业务API角色不运行调度或消息消费者。消费成功后Outbox标记CONSUMED；已发送但超过应到时间60秒仍未确认的记录会补投，最多10轮后FAILED供排查。

延迟事件使用1秒/10秒/1分钟/10分钟/1小时固定TTL队列，再由死信路由回工作队列。提前唤醒的事件重新安排剩余延时；所有处理都检查实际截止时间和订单，XXL扫描兜底。死信队列保留无法处理的消息，不自动无限循环未知消息。

## 证据与资源

完整集成测试报告在 engine/target/surefire-reports 和 gateway/target/surefire-reports。验收摘要见 docs/evidence。

原生包、JAR、日志和备份会占用磁盘空间。长期运行时请为日志和备份配置保留期限。

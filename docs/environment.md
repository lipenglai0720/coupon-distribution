# 开发环境

使用 Java 17 和 Maven 3.9，IDEA 用户可选择 Bundled Maven。打开根 pom.xml 并导入两个模块。默认 Maven 配置启用 discovery 和 sharding，集成测试使用 sharded Spring Profile。

应用优先读取项目根目录或运行目录下的 application-local.properties。本地配置、账号种子和原始测试产物保存在 Git 忽略的目录。应用连接参数可通过属性或环境变量配置。

测试使用独立 quanshu_test 数据库与 Redis DB 15，测试数据库地址由 TEST_DB_URL 提供，Redis 端口由 TEST_REDIS_PORT 提供。若使用 Windows SSH 转发，这两个端口默认取 13306 和 16379。未使用转发时分别设为 MySQL 和 Redis 的实际端口。

Ubuntu 部署使用原生用户进程，不依赖 Docker。运行布局、转发和数据迁移见 [Ubuntu 用户目录部署](ubuntu-deployment.md)。

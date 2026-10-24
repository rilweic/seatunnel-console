# seatunnel-console

用来生成、调度和监控 SeaTunnel 任务的管理平台。设计见 [docs/design.md](docs/design.md)。

## 工程结构

```
seatunnel-console/
├── console-common       通用的返回结构 Result、ConsoleException、错误码
├── console-engine       EngineClient 接口；ZetaRestClient（SeaTunnel Zeta REST API v2 实现）
├── console-datasource   数据源插件接口（MySQL / PostgreSQL / Elasticsearch）
├── console-server       Spring Boot 应用：HTTP API、持久化（MyBatis-Plus + Flyway）、
│                        内嵌 XXL-JOB 执行器（seatunnelJobHandler）和 OpenAPI 客户端（scheduler 包）
├── console-ui           Vue 3 + Vite + Element Plus
├── docker/              docker-compose（MySQL、xxl-job-admin、console）和 MySQL 初始化脚本
└── Dockerfile           多阶段构建：先构建前端，再把前端打进 console-server 的 jar
```

**约束**：console 只通过 REST API 访问 SeaTunnel，不能依赖 SeaTunnel 的 jar（见 docs/design.md §1.2）。

## 版本

| 组件 | 版本 | JDK |
|---|---|---|
| console-server | Spring Boot 4.1.2、MyBatis-Plus 3.5.17 | 17 |
| xxl-job-admin | 3.5.0 | 17 |
| SeaTunnel 集群 | 3.0.0 | 8 或 11 |

## 本地开发

### 1. 准备依赖（自行安装）

| 依赖 | 要求 |
|---|---|
| MySQL 8.x | 库 `seatunnel_console`，以及一个对这个库有全部权限的账号，表结构由 Flyway 自动创建；库 `xxl_job`，导入 `docker/mysql/init/02-xxl-job.sql`（XXL-JOB 3.5.0 官方脚本，脚本里自带建库语句） |
| xxl-job-admin 3.5.0（JDK 17） | 连接上面的 `xxl_job` 库。默认账号 `admin/123456`，登录后要立刻修改密码 |
| xxl-job-admin 中的执行器 | 新建执行器，AppName 填 `seatunnel-console`，注册方式选"自动注册"，再设置一个 AccessToken，并记下这个执行器的 id |

xxl-job-admin 要能访问到 console 所在机器的 9999 端口（执行器端口）。如果它通过自动识别的 IP 访问不到，就设置 `CONSOLE_XXL_JOB_EXECUTOR_ADDRESS`。

暂时不想接 XXL-JOB 的话，设置 `CONSOLE_XXL_JOB_ENABLED=false` 即可启动。

> `docker/docker-compose.yml` 用来部署和交付整套环境，本地开发用不到它。

### 2. 启动 SeaTunnel 集群

按 SeaTunnel 文档以单节点集群启动，然后做三处修改（详见 docs/design.md §4.3）：

1. `config/seatunnel.yaml`：开启 Basic 认证（默认账号 `admin`，密码 `admin123`，和 `application.yml` 的默认值一致）
2. `config/log4j2.properties`：把 `rootLogger.appenderRef.file.ref` 改为 `routingAppender`，**否则 console 看不到 SeaTunnel 作业日志**
3. `lib/`：放入 MySQL 或 PostgreSQL 的 JDBC 驱动

> **Apple Silicon + Oracle JDK 8**：G1 在这个组合下是实验特性，SeaTunnel 默认的 `config/jvm_options` 会报错
> `VM option 'UseG1GC' is experimental`。解决办法是在 `-XX:+UseG1GC` 之前加一行 `-XX:+UnlockExperimentalVMOptions`，
> 或者改用 JDK 11。

### 3. 启动后端

本地配置统一放在 `dev.env`（已加入 .gitignore，里面有数据库密码和 `CONSOLE_SECRET_KEY`）。**`CONSOLE_SECRET_KEY` 丢失后，已保存的数据源密码就无法解密了。**

```bash
mvn -DskipTests install
set -a; source dev.env; set +a
java -jar console-server/target/seatunnel-console.jar     # http://localhost:8088
```

在 IDE 里运行 `ConsoleApplication` 时，把 `dev.env` 配置为环境变量文件。

第一次启动时会创建管理员账号 `admin`。没有设置 `CONSOLE_ADMIN_PASSWORD` 时会随机生成密码，**只在启动日志里打印一次**（日志里搜索 `initial administrator`）。

常用环境变量：

| 变量 | 默认值 |
|---|---|
| `CONSOLE_SECRET_KEY` | 无，**必填**：`openssl rand -base64 32` |
| `CONSOLE_ADMIN_USERNAME` / `CONSOLE_ADMIN_PASSWORD` | `admin` / 随机生成 |
| `CONSOLE_DATA_DIR` | `./data`（SeaTunnel 日志的归档目录） |
| `CONSOLE_DB_URL` / `CONSOLE_DB_USERNAME` / `CONSOLE_DB_PASSWORD` | `jdbc:mysql://localhost:3306/seatunnel_console` / `console` / `console` |
| `CONSOLE_ZETA_MASTER_URLS` | `http://localhost:8080`（多个地址用逗号分隔） |
| `CONSOLE_ZETA_USERNAME` / `CONSOLE_ZETA_PASSWORD` | `admin` / `admin123` |
| `CONSOLE_XXL_JOB_ENABLED` | `true` |
| `CONSOLE_XXL_JOB_ADMIN_ADDRESSES` | `http://localhost:8081`（多个地址用逗号分隔） |
| `CONSOLE_XXL_JOB_APPNAME` / `CONSOLE_XXL_JOB_ACCESS_TOKEN` / `CONSOLE_XXL_JOB_GROUP_ID` | `seatunnel-console` / 空 / `0` |
| `CONSOLE_XXL_JOB_EXECUTOR_PORT` / `CONSOLE_XXL_JOB_EXECUTOR_ADDRESS` | `9999` / 空（自动识别） |

### 4. 启动前端

```bash
cd console-ui
npm install
npm run dev        # http://localhost:5173，/api 请求代理到 8088
```

### 5. 验证

```bash
curl localhost:8088/actuator/health
curl localhost:8088/api/cluster/overview     # 连不上集群时返回 HTTP 503
```

## 使用流程（P1）

1. **项目**：新建一个项目
2. **数据源**：新建 MySQL、PostgreSQL 或 ES 数据源，先点"测试连接"，再保存
3. **任务**：新建离线任务，选择"向导"或"脚本"。
   - **向导**（MySQL / PostgreSQL → MySQL / PostgreSQL / ES）：按 来源 → 目标 → 字段与同步方式 → 并行度与校验 → 预检查与预览 五步填写，console 生成配置并在保存前预检查。可以随时"转为脚本模式"（不可逆）
   - **脚本**：在脚本编辑器里写 HOCON。在 source 或 sink 里点"插入数据源"，会插入 `datasource_id = "..."`，运行时由 console 注入连接信息。保存为新版本
4. 点**立即运行**，跳转到运行详情页：查看事件时间线和 SeaTunnel 作业日志（运行中每 4 秒刷新，结束后从归档读取）。运行中或等待中的实例可以点**停止**，会同时停止 SeaTunnel 上的作业
5. **调度**：在任务详情的"调度"页填 cron（Quartz 6 段语法，如 `0 0 2 * * ?`），设置失败重试、超时和阻塞策略后保存，console 会同步到 XXL-JOB。同步失败时页面会显示原因，重新保存即可

6. **变量**：在配置里写 `${名称}` 或时间表达式 `$[yyyyMMdd-1]`。参数可以在项目、任务和启动这三层设置，以计划时间为基准，写法和 DolphinScheduler 相同（详见 docs/design.md §8）。点"预览渲染结果"可以查看某个计划时间下的变量值和最终配置

7. **重跑**：在运行详情页点"重跑"，用同样的计划时间和启动参数再跑一次，可以选原版本或当前版本
8. **补数**：在任务详情页点"补数"，选择日期范围，console 按调度的 cron（没有调度时每天 00:00）逐个运行，进度在"补数"标签页查看，可以取消；XXL-JOB 不可用时会暂停，恢复后点"继续"

9. **行数校验**：在脚本编辑器下方打开"行数校验"，源端可以自动推导，目标端写计数 SQL（或填 ES 索引）。可以先点"测试校验"看结果，保存后每次运行成功都会自动比较，结果显示在运行列表和详情页

目前**还没有**的功能：实时（CDC）任务、告警（P2）。

## 测试

```bash
mvn test
```

## 已核实的 Zeta 3.0.0 REST 行为

- `/submit-job` 返回的 `jobId` 是**字符串**（文档示例写的是数字）。jobId 会超出 JavaScript 能精确表示的整数范围，所以前端必须一律按字符串处理。
- 用 `/job-info/{id}` 查询不存在或已过期的任务时，只返回 `{"jobId": "..."}`，没有 `jobStatus` 字段。
- 开启 Basic 认证后，不带认证信息的请求返回 401。

## 打包镜像

```bash
docker compose -f docker/docker-compose.yml up -d --build
```

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

## 依赖与版本

| 软件 | 版本 | 用途 | 获取方式 |
|---|---|---|---|
| JDK | **17** | 编译和运行 console | 任意发行版（Temurin、Oracle 等） |
| Maven | 3.9+ | 编译后端 | 官网下载 |
| Node.js | **20.19+ 或 22.12+**（Vite 8 的要求） | 编译前端 | 官网下载 |
| MySQL | 8.x（Docker 部署用的是 8.4） | console 自己的库 `seatunnel_console`、XXL-JOB 的库 `xxl_job` | 自行安装，或用 Docker 部署里的 MySQL |
| xxl-job-admin | **3.5.0**（JDK 17） | 调度 | Docker 镜像 `xuxueli/xxl-job-admin:3.5.0`，或从 [xxl-job](https://github.com/xuxueli/xxl-job) 3.5.0 源码打包 |
| SeaTunnel | **3.0.0**，Zeta 集群模式（JDK 8 或 11，不要用 17） | 执行同步作业 | [官网下载页](https://seatunnel.apache.org/download) 的二进制包 |
| JDBC 驱动 | MySQL Connector/J 8.x、PostgreSQL 42.x | 放进 SeaTunnel 的 `lib/` | Maven 中央仓库 |
| Docker / Docker Compose | 较新版本即可 | 只在 Docker 部署时需要 | — |

console 只通过 REST API 访问 SeaTunnel，不依赖 SeaTunnel 的 jar，所以 console 和 SeaTunnel 可以用不同的 JDK。

**SeaTunnel 集群不在本仓库里，也不在 docker-compose 里**，两种部署方式都要先按下面「准备 SeaTunnel 集群」装好。

## 快速开始

有两条路线：

- **A. 用 Docker 部署**：MySQL、xxl-job-admin、console 一起起来，建库、建 XXL-JOB 执行器都是自动的。适合试用和交付
- **B. 本地开发**：自己装 MySQL 和 xxl-job-admin，后端用 jar 或 IDE 启动，前端用 Vite 开发服务器

两条路线都先做第 0 步。

### 0. 准备 SeaTunnel 集群（两条路线都需要）

1. 下载并解压 SeaTunnel 3.0.0 二进制包，用 JDK 8 或 11
2. 安装连接器插件：确认 `config/plugin_config` 里包含 `connector-jdbc`、`connector-elasticsearch`、`connector-fake`、`connector-console`（后两个用于"集群健康"页的运行诊断），执行 `bin/install-plugin.sh`，完成后 `connectors/` 下应有这几个 jar
3. 把 MySQL / PostgreSQL 的 JDBC 驱动放进 `lib/`（集群里**每个节点**都要放）
4. 修改 `config/seatunnel.yaml`，打开 REST API v2 和 Basic 认证（console 默认用 `admin` / `admin123`，可以通过 `CONSOLE_ZETA_USERNAME`、`CONSOLE_ZETA_PASSWORD` 修改）：

   ```yaml
   seatunnel:
     engine:
       http:
         enable-http: true
         port: 8080
         enable-basic-auth: true
         basic-auth-username: admin
         basic-auth-password: admin123
   ```

5. 修改 `config/log4j2.properties`：把 `rootLogger.appenderRef.file.ref` 改为 `routingAppender`（**每个节点**都要改），**否则 console 看不到 SeaTunnel 作业日志**
6. 以集群模式启动（`-m local` 模式没有 REST 服务）：

   ```bash
   ./bin/seatunnel-cluster.sh -d
   curl -u admin:admin123 http://localhost:8080/overview     # 返回集群信息即可
   ```

> **Apple Silicon + Oracle JDK 8**：G1 在这个组合下是实验特性，SeaTunnel 默认的 `config/jvm_options` 会报错
> `VM option 'UseG1GC' is experimental`。在 `-XX:+UseG1GC` 之前加一行 `-XX:+UnlockExperimentalVMOptions`，或者改用 JDK 11。

完整的集群要求（多 master、checkpoint 存储、作业过期时间等）见 [docs/design.md](docs/design.md) §4.3。

### A. 用 Docker 部署

`docker/docker-compose.yml` 一次启动 MySQL、xxl-job-admin 和 console（前端已打包进 console 的 jar，镜像在本机构建）。SeaTunnel 集群要先按第 0 步准备好。

```bash
cd docker
cp .env.example .env      # 填写密码、CONSOLE_SECRET_KEY、XXL_JOB_ACCESS_TOKEN、SeaTunnel 地址
docker compose up -d --build
```

- 打开 `http://<主机>:8088`，用 `admin` 和 `.env` 里的 `CONSOLE_ADMIN_PASSWORD` 登录
- `CONSOLE_SECRET_KEY` 要另外备份，丢了已保存的数据源密码就解不开
- MySQL 首次初始化时会自动创建 console 的库和账号，以及 XXL-JOB 的执行器分组 `seatunnel-console`（id 10，带上 `XXL_JOB_ACCESS_TOKEN`），不需要再到 xxl-job-admin 里手动建
- xxl-job-admin 的界面在 8081 端口，默认账号 `admin/123456`，登录后立刻改密码
- **网络**：console 容器要能访问 SeaTunnel 的 REST 端口和各个数据源；SeaTunnel 的 worker 也要能访问数据源。数据源地址要从这两边都能连通，不要写 `127.0.0.1`
- console 的作业日志归档和 XXL-JOB 执行器日志在数据卷 `console-data`、`console-logs` 里
- 部署后打开"集群健康"页检查一遍，再点"运行诊断"

### B. 本地开发

#### 1. 准备 MySQL 和 xxl-job-admin

| 依赖 | 要求 |
|---|---|
| MySQL 8.x | 建库 `seatunnel_console`（`utf8mb4`）和一个对这个库有全部权限的账号，表由 console 启动时通过 Flyway 自动创建，不用手工建表；库 `xxl_job`，导入 `docker/mysql/init/02-xxl-job.sql`（XXL-JOB 3.5.0 官方脚本，脚本里自带建库语句） |
| xxl-job-admin 3.5.0（JDK 17） | 连接上面的 `xxl_job` 库。默认账号 `admin/123456`，登录后要立刻修改密码 |
| xxl-job-admin 中的执行器 | 新建执行器，AppName 填 `seatunnel-console`，注册方式选"自动注册"，再设置一个 AccessToken，并记下这个执行器的 id |

不想从源码打包 xxl-job-admin 的话，可以直接用官方镜像（把数据库地址和密码换成自己的）：

```bash
docker run -d --name xxl-job-admin -p 8081:8080 \
  -e PARAMS="--spring.datasource.url=jdbc:mysql://<MySQL 地址>:3306/xxl_job?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai --spring.datasource.username=root --spring.datasource.password=<密码>" \
  xuxueli/xxl-job-admin:3.5.0
```

xxl-job-admin 要能访问到 console 所在机器的 9999 端口（执行器端口）。如果它通过自动识别的 IP 访问不到，就设置 `CONSOLE_XXL_JOB_EXECUTOR_ADDRESS`。

暂时不想接 XXL-JOB 的话，设置 `CONSOLE_XXL_JOB_ENABLED=false` 即可启动。

> `docker/docker-compose.yml` 用来部署和交付整套环境，本地开发用不到它。

#### 2. 编译并启动后端

本地配置放在 `dev.env`：复制模板 `cp dev.env.example dev.env` 再填写（`dev.env` 已加入 .gitignore，里面有数据库密码和 `CONSOLE_SECRET_KEY`）。**`CONSOLE_SECRET_KEY` 丢失后，已保存的数据源密码就无法解密了。**

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
| `CONSOLE_RUN_POLL_INTERVAL` | `2s`（运行状态及指标采样间隔） |
| `CONSOLE_XXL_JOB_ENABLED` | `true` |
| `CONSOLE_XXL_JOB_ADMIN_ADDRESSES` | `http://localhost:8081`（多个地址用逗号分隔） |
| `CONSOLE_XXL_JOB_APPNAME` / `CONSOLE_XXL_JOB_ACCESS_TOKEN` / `CONSOLE_XXL_JOB_GROUP_ID` | `seatunnel-console` / 空 / `0` |
| `CONSOLE_XXL_JOB_EXECUTOR_PORT` / `CONSOLE_XXL_JOB_EXECUTOR_ADDRESS` | `9999` / 空（自动识别） |

#### 3. 启动前端

```bash
cd console-ui
npm install
npm run dev        # http://localhost:5173，/api 请求代理到 8088
```

#### 4. 验证

浏览器打开 http://localhost:5173 ，用管理员账号登录，再打开"集群健康"页检查 SeaTunnel、XXL-JOB 和数据目录，点"运行诊断"确认作业日志和 JDBC 驱动都正常。也可以用命令检查：

```bash
curl localhost:8088/actuator/health          # {"status":"UP"}；其他接口都要先登录
```

## 账号与权限

- 第一次启动自动创建管理员 `admin`。管理员在"用户管理"里新建其他用户并设初始密码，对方首次登录时必须修改
- 所有人都能看到全部内容，但只能修改、删除、运行**自己创建的**项目、数据源和任务（管理员不受限）；任务只能使用创建人自己的数据源
- 有人离职或交接时，管理员在"用户管理"里把他名下的内容整体转给另一个人

## 使用流程（P1）

登录后默认进入「概览」，看全部任务今天和近两周的运行情况、正在运行和最近失败的任务，以及接下来的调度；可以切到「我的」，也可以全屏放到大屏上。左侧菜单分为「数据同步」（任务、运行实例）、「资源配置」（项目、数据源）和「运维管理」（集群健康、用户管理、加密密钥）。

1. **项目**：新建一个项目
2. **数据源**：新建 MySQL、PostgreSQL 或 ES 数据源，先点"测试连接"，再保存
3. **任务**：新建离线任务，选择"向导"或"脚本"，创建后进入编辑页。以后从任务列表点进去是任务详情（概览、运行记录、补数、版本，只看不改），要修改点右上角**编辑**。编辑页分"同步配置""调度""基本信息"三部分，各自保存，只有同步配置会生成新版本。
   - **向导**（MySQL / PostgreSQL → MySQL / PostgreSQL / ES）：按 来源 → 目标 → 字段与同步方式 → 并行度与校验 → 预检查与预览 五步填写，任何一步都可以点"保存为新版本"，console 会先预检查再保存。可以随时"转为脚本模式"（不可逆）
     - 第 2 步「表映射」：每张源表写入哪张目标表，可以按规则批量改名，也可以逐行改
     - 第 3 步「字段映射」：左边选表，右边逐个字段屏蔽、改名，或点「处理」做值替换、脱敏、类型转换、加密；「新增字段」可以加常量、变量或计算出来的字段；「提取字段」可以从 JSON、正则分组或分隔符里取出新字段。都会生成为 SeaTunnel 的 transform
     - 第 4 步的高级参数都有中文名称和说明，不常用的折叠在「更多参数」里
   - **脚本**：在脚本编辑器里写 HOCON。在 source 或 sink 里点"插入数据源"，会插入 `datasource_id = "..."`，运行时由 console 注入连接信息；点"插入 transform"可以插入带中文注释的模板。保存为新版本
   - **复制**：任务列表的"复制"或任务详情的"更多 → 复制任务"，把当前版本复制成一个新任务（调度设置一起复制但不启用）
4. 在任务详情点**立即运行**，跳转到运行详情页：查看运行指标（读写行数、吞吐量曲线、表级指标）、事件时间线和 SeaTunnel 作业日志（运行中每 4 秒刷新，结束后从归档读取）。任务详情的"概览"汇总近 7 天成功率、耗时趋势、同步链路和正在运行的进度。运行中或等待中的实例可以点**停止**，会同时停止 SeaTunnel 上的作业
5. **调度**：在编辑页的"调度"里按频率选运行时间（每隔几分钟 / 每小时 / 每天 / 每周 / 每月，也可以自定义 cron），两次运行至少间隔 2 分钟；设置失败重试、超时和阻塞策略后保存，console 会同步到 XXL-JOB。同步失败时页面会显示原因，重新保存即可

6. **变量**：在配置里写 `${名称}` 或时间表达式 `$[yyyyMMdd-1]`。参数可以在项目、任务和启动这三层设置，以计划时间为基准，写法和 DolphinScheduler 相同（详见 docs/design.md §8）。填参数时按类型选值（固定值、日期时间、内置变量或自定义表达式），每行显示示例值和引用写法。点"预览渲染结果"可以查看某个计划时间下的变量值和最终配置

7. **重跑**：在运行详情页点"重跑"，用同样的计划时间和启动参数再跑一次，可以选原版本或当前版本
8. **补数**：在任务详情页点"补数"，选择日期范围（最晚到今天，只补已经到点的运行），console 按调度的 cron（没有调度时每天 00:00）逐个运行，进度在"补数"标签页查看，可以取消；同一个任务同时只能有一个未结束的补数；XXL-JOB 不可用时会暂停，恢复后点"继续"

9. **字段加密**：管理员先在"加密密钥"里新建（自动生成或导入已有密钥，创建后不能再查看），向导里给字段勾选"加密"并为这张表选择密钥。配置里只保存密钥 ID，运行时才注入

10. **行数校验**：在脚本编辑器下方打开"行数校验"，源端可以自动推导，目标端写计数 SQL（或填 ES 索引）。可以先点"测试校验"看结果，保存后每次运行成功都会自动比较，结果显示在运行列表和详情页

目前**还没有**的功能：实时（CDC）任务、告警（P2）。

## 测试

```bash
mvn test
```

## 已核实的 Zeta 3.0.0 REST 行为

- `/submit-job` 返回的 `jobId` 是**字符串**（文档示例写的是数字）。jobId 会超出 JavaScript 能精确表示的整数范围，所以前端必须一律按字符串处理。
- 用 `/job-info/{id}` 查询不存在或已过期的任务时，只返回 `{"jobId": "..."}`，没有 `jobStatus` 字段。
- 开启 Basic 认证后，不带认证信息的请求返回 401。

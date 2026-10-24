# seatunnel-console 设计文档

> 状态：已确认（2026-10-23）
> 范围：总体设计与分阶段规划。实现细节（表结构、接口定义）在开发阶段另行补充。

## 1. 背景与目标

做一个用来生成和管理 Apache SeaTunnel 任务的平台。提供以下能力：

- **配置任务**：面向新手的场景化向导，同时提供脚本模式
- **调度与启停**：离线任务按时调度、重跑、补数；实时任务启停（P2）
- **查看执行情况**：运行实例的状态、指标、事件、日志，以及数据校验

设计参考 [apache/seatunnel-web](https://github.com/apache/seatunnel-web) 和 [Tapdata](https://docs.tapdata.io/data-replication/create-task)：借鉴领域模型和关键模块的设计，选择性摘抄 seatunnel-web 的代码（Apache 2.0，保留 License 头），**不 fork**。

### 1.1 工程位置与外部依赖

- 工程目录：`data-integration/seatunnel-console`
- `data-integration/seatunnel/` 只是上游源码，用来查阅，**不修改，也不作为编译依赖**
- SeaTunnel 按**单独安装的发行包**对待，console 和它之间只通过 REST API 交互

### 1.2 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot 4.1（JDK 17）。原定 Spring Boot 3，但 3.x 的开源维护已在 2026-06 结束，搭建工程时改为 4.1 |
| 前端 | Vue 3 |
| 元数据库 | MySQL |
| 调度 | XXL-JOB ≥ 3.5.0 |
| 执行引擎 | SeaTunnel Zeta 集群（P1/P2），Flink、Spark（P3） |

**目标 SeaTunnel 版本：3.0.0**（已在 `v3.0.0` tag 上核对过：本文用到的 REST 接口、`restoreMode`/`restoreSourceJobId`、Basic 认证、REST 提交时的 ConfigShade 解密、日志敏感字段打码、`seatunnel-metadata-export.sh` 都存在）。

**JDK 版本**：

| 组件 | JDK | 依据 |
|---|---|---|
| SeaTunnel 3.0.0 集群 | **11** | 编译目标是 Java 8；官方文档写的是 "Java 8 或 Java 11"；CI 只测了 8 和 11。没有测过 17，不要用 17 |
| seatunnel-console | **17** | Spring Boot 4 最低要求 17 |
| xxl-job-admin 3.5.x | **17** | XXL-JOB 从 3.0 起基于 Spring Boot 3 和 JDK 17 |

不能统一用一个 JDK：统一用 17，SeaTunnel 3.0.0 没测过；统一用 11，就用不了 Spring Boot 3 和 XXL-JOB 3.5（拿不到 OpenAPI）。分开用没有问题，因为 console 和 SeaTunnel 跑在各自的 JVM 里，只通过 REST API 通信，也不依赖 SeaTunnel 的 jar。

**约束：不能在 console 进程里内嵌 SeaTunnel 的 jar**，否则要重新评估兼容性。以后升级到主干版本（编译目标 Java 11，支持 11 和 17）时，可以再考虑统一用 17。

## 2. 总体架构

```
┌──────────────────────────── seatunnel-console（单实例）────────────────────────────┐
│  Vue 3 前端                                                                        │
│  ─────────────────────────────────────────────────────────────────────────────── │
│  账号/项目 │ 数据源中心 │ 任务定义+版本 │ 运行实例 │ 集群健康检查 │ 元数据导入       │
│  ─────────────────────────────────────────────────────────────────────────────── │
│  变量渲染 │ HOCON 生成 │ 连接信息注入（内存） │ 行数校验 │ 状态/指标轮询             │
│  ─────────────────────────────────────────────────────────────────────────────── │
│  EngineClient（P1：ZetaRestClient）     │  XXL-JOB 执行器（seatunnelJobHandler）     │
└──────────────┬──────────────────────────┴──────────────────┬─────────────────────┘
               │ REST API v2（HTTPS + Basic Auth）             │ 注册 / 回调
               ▼                                               ▼
     SeaTunnel Zeta 集群（多 master）                    xxl-job-admin
                                                          ▲
               console ── OpenAPI 同步调度配置 ──────────────┘
```

## 3. 核心概念

| 概念 | 说明 |
|---|---|
| 用户 | 简单的账号登录，分管理员和普通用户两种角色。不做多租户；按创建人隔离修改权限，详见 §3.1 |
| 项目 | 用来给任务分组，并承载项目参数。**不做权限隔离** |
| 数据源 | 全局共享。连接信息加密存储 |
| 任务定义 | 属于某个项目，类型为批（P1）或流（P2）。有表单模式和脚本模式 |
| 任务版本 | 每次保存生成一个不可变的版本，包含表单 JSON 和 HOCON（只含 `datasource_id`） |
| 调度配置 | cron、重试次数、超时时间、阻塞策略。以 console 为准，同步到 XXL-JOB |
| 运行实例 | 一次实际的运行。记录绑定的版本、计划时间、最终变量值、打码后的配置、Zeta jobId、状态、指标、事件、日志归档、校验结果 |
| connector 元数据 | 来自官方导出的 JSON，按 SeaTunnel 版本存储 |

代码从 P1 开始就按"任务类型：批 / 流"做好抽象，避免 P2 时大改。

### 3.1 账号与权限（2026-10-24 实现）

- **可见性**：所有登录用户都能看到全部项目、数据源、任务和运行记录（列表默认只看"我的"，可以切到"全部"）
- **修改权**：项目、数据源、任务只有**创建人或管理员**能修改、删除、运行；运行记录和调度跟着任务走（停止、重跑、补数、改调度都按任务的创建人判断）。数据源的测试连接、浏览库表也算"使用凭据"，同样只限创建人或管理员
- **只能用自己的数据源**：任务引用的数据源（包括行数校验用到的）必须属于任务的创建人，管理员编辑别人的任务时也一样；只能在自己的项目里建任务
- **管理员专属**：用户管理、运行集群诊断、把一个用户名下的项目 + 数据源 + 任务**整体**转给另一个用户（不做单个转移）
- **账号**：管理员建用户并设初始密码，首次登录必须修改（服务端拦截除 `/api/auth/**` 以外的接口，错误码 1005）；管理员重置密码后同样必须修改；用户只禁用不删除；至少保留一个启用的管理员
- **密码与登录**：至少 8 位且含字母和数字；同一账号连续输错 5 次锁定 10 分钟（内存中，重启解除；管理员重置密码时解锁）
- 旧数据（创建人为空）由迁移 V5 归给第一个管理员

## 4. 执行引擎

### 4.1 选择：Zeta + REST API v2

对比了三种提交方式（源码调研结论）：

| | shell 命令（DolphinScheduler 的做法） | Java 客户端（seatunnel-web 的做法） | **REST API（选定）** |
|---|---|---|---|
| console 机器上要装 SeaTunnel | 要 | 要依赖 SeaTunnel 的 jar | 不需要 |
| 和 SeaTunnel 版本耦合 | 低 | 高（seatunnel-web 因此停在 2.3.11） | 低 |
| 实时任务 savepoint / 指标 | 能做，但要解析命令输出 | 支持 | 支持 |

- DolphinScheduler（`SeatunnelTask`）：执行 `seatunnel.sh --config ... -m cluster`，阻塞等待进程退出码；停止就是 kill 进程。没有 savepoint，也没有指标。
- seatunnel-web（`SeaTunnelEngineProxy`）：内嵌 `SeaTunnelClient`，通过 Hazelcast 连接集群，用线程阻塞在 `waitForJobCompleteV2`。没有定时调度。

用到的接口：`/submit-job`（支持 `restoreMode` 和 `restoreSourceJobId`）、`/stop-job`（`isStopWithSavePoint`）、`/job-info/{jobId}`、`/finished-jobs`、`/logs/{jobId}`、`/overview`。

### 4.2 EngineClient 抽象（长期约束：支持 Flink 和 Spark）

- 第一阶段只实现 `ZetaRestClient`
- **P3 要支持 Flink 和 Spark**。SeaTunnel 的 REST API 是 Zeta 独有的，Flink 和 Spark 要用 `start-seatunnel-flink-*-connector-v2.sh` / `start-seatunnel-spark-*-connector-v2.sh`，或者引擎自己的 API（Flink JobManager REST、YARN、Livy），每种引擎要单独实现
- 连接信息由 console 渲染（第 6 节），和引擎无关，Flink 和 Spark 也能沿用

### 4.3 SeaTunnel 集群的部署前提

写进部署文档，并由"集群健康检查"页面尽可能自动检查：

1. 以 **Zeta 集群**方式常驻运行（混合部署或分离部署）。`-m local` 模式没有 REST 服务
2. `seatunnel.yaml` 中设置 `seatunnel.engine.http.enable-http: true`（代码默认是 false），默认端口 8080
3. 开启 `enable-basic-auth`，建议同时开启 HTTPS
4. **每个节点**的 `connectors/` 和 `lib/` 下放齐所需的 connector 及其依赖，比如 JDBC 驱动。REST 提交时配置在服务端解析
5. `history-job-expire-minutes` 默认 1440，任务结束一天后集群就查不到它了，所以 console 要自己持久化实例数据
6. 实时任务（P2）：checkpoint 放共享存储（HDFS、S3、OSS），建议开启 IMap 持久化
7. console 里配置多个 master 地址，一个连不上就换下一个
8. **每个节点都要开启按作业拆分日志**：在 `config/log4j2.properties` 里把 `rootLogger.appenderRef.file.ref` 改为 `routingAppender`（3.0.0 默认是 `fileAppender`）。否则 `/logs/{jobId}` 返回空列表，console 看不到 SeaTunnel 作业日志（2026-10-24 实测）
9. 用到 Jdbc 连接器时，每个节点的 `lib/` 下都要放对应的 JDBC 驱动
10. SeaTunnel 在**接收任务时**就会校验数据源，比如 Jdbc 的 query 引用了不存在的表。这类失败直接返回 HTTP 500，**响应里没有根因**，也不会生成作业日志；根因只写在 master 节点的 `logs/seatunnel-engine-server.log` 里。console 会把这类失败标记为"提交被拒绝"，并在页面上给出这个提示

健康检查（"集群健康"页，2026-10-24 实现）分两层：

- **快速检查**（打开页面自动执行，不提交作业）：逐个 master 是否连通、是否开启认证、版本是否为 3.0；worker 节点；所需连接器是否已安装（向导用到的 Jdbc source/sink、Elasticsearch sink，加上所有任务当前版本实际引用的连接器，缺失时列出受影响的任务；通过 `/option-rules` 返回 404 判断，只能检查响应的那个 master）；XXL-JOB admin 是否可访问；console 数据目录是否可写
- **运行诊断**（手动触发）：同时提交几个很小的真实作业。FakeSource → Console 作业结束后能否读到它的日志（检查第 8 条 routingAppender）；每个 MySQL / PostgreSQL 数据源一个 `select 1` 作业（检查第 9 条驱动，以及 worker 到数据库的网络）。最多等 90 秒，超时的作业会被停止

## 5. 调度：XXL-JOB

### 5.1 选型理由

- 调度器只负责"按时调一次 `/submit-job`"，真正执行在 Zeta 上
- XXL-JOB **3.5.0** 开始提供任务增删改、启停和触发的 OpenAPI（AccessToken 改为按执行器隔离）。**低于 3.5.0 的版本不能用**
- 可以直接得到 cron、错过触发后的补偿、重试、超时、阻塞策略、调度日志和高可用
- 没选 DolphinScheduler：它很重，界面不方便；它的 SeaTunnel 插件就是执行一条 shell 命令，管不了实时任务。只有需要复杂 DAG 依赖时才值得引入，目前没有这个需求

### 5.2 集成方式

- console 内嵌 `xxl-job-core` 作为执行器，只注册一个处理器 `seatunnelJobHandler`，参数里带 console 的任务 ID 和计划时间
- **任务配置以 console 为准**：用户在 console 里配置调度，console 通过 OpenAPI 同步到 XXL-JOB。xxl-job-admin 的界面只给管理员看日志、排查问题
- **只有离线任务走 XXL-JOB**。实时任务由 console 直接启停（P2）
- 处理器的执行流程：
  1. 创建运行实例，绑定版本
  2. 渲染变量（第 8 节），在内存中注入连接信息（第 6 节）
  3. 调 `/submit-job`
  4. **阻塞轮询** `/job-info`（间隔 10 秒），直到任务结束
  5. 写入最终状态、指标，归档日志，执行行数校验（第 10 节）
  6. 根据结果返回成功或失败，让 XXL-JOB 的超时和阻塞策略生效
- **失败重试由 console 处理器在同一个运行实例内完成，XXL-JOB 的失败重试次数固定为 0**（2026-10-23 改动）。原因是 XXL-JOB 每次重试都会生成一条新的调度日志，处理器无法把它和上一次失败关联起来。每次尝试在 `job_instance_attempt` 里记一条，有各自的 Zeta jobId 和日志，详见 [schema.md](schema.md) §4.2
- 手动运行、重跑和补数时，由 console 先创建运行实例，再把 `instanceId` 放进执行参数，详见 schema.md §4.1
- console 和 XXL-JOB 之间要做对账：发现两边的调度配置不一致时，以 console 为准修正（2026-10-24 实现）。XXL-JOB 的 OpenAPI 没有查询接口，所以对账就是"把 console 的状态再推一遍"：每 10 分钟（启动 2 分钟后第一次）把所有调度重新 update + start/stop，这也会重试之前同步失败的调度；在 admin 里被删掉的任务会重建并恢复调度；没有调度的任务用 stop 探测是否还在，不在就清掉引用，下次运行时重建；console 里已删除、当时没能删掉 XXL 任务的，补删。手动运行、重跑、补数触发时发现任务已被删，也会当场重建再触发。最近一次对账结果显示在"集群健康"页。判断"任务不存在"依赖 admin 的提示文字（中文、繁体、英文三种都识别）
- **超时和手动终止**：XXL-JOB 因超时或手动终止而中断处理器线程时，处理器会同时停止 Zeta 上对应的任务（不做 savepoint），并记为 CANCELED，避免任务在集群上继续运行却没人知道。调用停止接口前要先清掉线程的中断标记，否则 JDK HttpClient 会立即抛异常，停止请求根本发不出去（2026-10-24 实测修正）
- **在 console 上停止运行**：PENDING 的实例直接用条件更新改为 CANCELED，XXL-JOB 之后再触发时会因为状态不是 PENDING 而放弃。RUNNING 的实例由执行它的线程负责停止：console 给该线程发取消信号，线程立即醒来，先查一次 Zeta 状态（任务若已在两次轮询之间结束，就记录真实结果，不误记为 CANCELED），再调用停止接口，记为 CANCELED。取消信号只在本进程内存里，所以要求 console 单实例部署
- **调度配置的同步**：保存调度时先写 `job_schedule`（sync_status=PENDING），再把任务对应的那一个 XXL-JOB 任务改为 CRON，启用时 start、停用时 stop。同步失败时记为 FAILED 并保存错误信息，用户修正后重新保存即可。cron 用 Quartz 语法（6 段，日和周必须有一个是 `?`，不支持年字段），console 在本地先校验并预览接下来 5 次触发时间
- **调度的超时和阻塞策略对手动运行同样生效**，因为手动运行触发的是同一个 XXL-JOB 任务。失败重试只对定时运行生效
- **XXL-JOB 会提前 5 秒预读触发**（`PRE_READ_MS`）：在下一次触发前 5 秒内停用调度，这一次仍可能会触发
- **计划时间**：定时触发时用 XXL-JOB 触发日志的时间；手动运行、重跑和补数时，由 console 在执行参数里明确传入 `scheduledTime`
- 执行参数格式：`{"jobDefinitionId": 12, "scheduledTime": "2026-10-22T02:00:00", "params": {...}}`
- 执行器设置 `glueEnabled=false`，不执行从 admin 界面下发的脚本，只运行 BEAN 处理器

## 6. 数据源与凭据安全

**原则：保存下来的任何配置里都不出现密码。**

1. 任务定义和各个版本里**只保存 `datasource_id`**
2. **只在提交那一刻**，在内存中把连接信息渲染进 HOCON，然后直接发给 `/submit-job`
3. 运行实例上归档的"实际执行配置"保存的是**打码后的版本**
4. 不管是 XXL-JOB 处理器还是 console，日志里都不能打印渲染后的配置
5. 和集群通信必须开启 HTTPS 和 Basic 认证

依据：`/job-info` 返回的 `jobDag` 只包含 `envOptions`、vertex 名称和表路径，不包含 connector 参数；SeaTunnel 打日志时会按 `ConfigShadeUtils.DEFAULT_SENSITIVE_KEYWORDS`（password、username、auth、token、access_key、secret_key）自动打码。

**可选加固（暂不做）**：REST 提交支持 `ConfigShade`（`RestUtil` 会调用 `ConfigShadeUtils`）。console 先用 AES 加密密码，在配置里写上 `shade.identifier = "aes"`，Zeta 节点上放一个解密 jar 即可。

**没采用的方案**：用 Metadata SPI（`MetadataProvider` + `metadata_datasource_id`）做一个插件，让集群节点直接读 console 的数据库。否决原因：链路长，每个节点都要放数据库账号和密钥，而且元数据 SPI 只支持 Zeta，和 Flink/Spark 的长期约束冲突。

### 6.1 数据源类型

| 阶段 | 类型 |
|---|---|
| P1 | MySQL、PostgreSQL、Elasticsearch |
| P3 | 按需扩展 |

每种数据源类型要实现：测试连接、列出库/表/索引、读取表结构，以及"数据源 → 对应 connector 配置项"的翻译。例如 Jdbc 用 `url/driver/user/password`，CDC 用 `base-url/username/password`。

## 7. 任务配置

### 7.1 connector 元数据

- 指的是连接器配置参数的**定义**（不是用户填的值）：参数名、类型、默认值、是否必填、官方说明
- 来源（2026-10-24 改）：console 直接调用集群的 `GET /option-rules?type=source|sink&plugin=<名称>`，按插件缓存在内存里。不再导出 JSON 上传，`seatunnel_metadata` 表不再使用
- **用途只限于**渲染向导里默认折叠的"高级参数"，以及填写时的类型提示。**不直接拿它当用户界面**

### 7.2 场景化向导（面向新手）

用户回答的是业务问题，不需要认识 SeaTunnel 的参数名。每个选项的中文文案、默认值，以及它们和 SeaTunnel 参数的对应关系，都由我们自己维护。**HOCON 由服务端根据表单生成**，前端只负责收集表单。

支持的组合（P1）：源端 MySQL、PostgreSQL；目标端 MySQL、PostgreSQL、Elasticsearch。ES 作为源端只能用脚本模式。

| 步骤 | 内容 |
|---|---|
| ① 来源 | 选数据源 → 选一个库（MySQL）或 schema（PG）→ 勾选一张或多张表（console 实时查询，标出有无主键）。**正则只用来筛选列表**，保存的是勾选出来的明确表清单，不会自动纳入以后新建的表 |
| ② 目标 | 选目标数据源 → 库或 schema（必须已存在，不自动建库）。单表时可以自定义目标表名或索引名；多表时目标名 = 前缀 + 源表名 + 后缀（生成 `table = "前缀${table_name}后缀"`）。目标端选项见 7.4 |
| ③ 字段与同步方式 | 默认每张表同步全部字段、全量同步；每张表可以展开：去掉字段、写一个固定过滤条件、单独指定增量字段。增量见 7.5。**不推算目标类型**：只显示源字段和类型；目标表已存在时对比字段，标出目标表缺少的字段。建表交给 SeaTunnel 的 `schema_save_mode` |
| ④ 并行度与校验 | 并行度 1/2/4/8，默认 1（单表按主键自动切分；多表时多张表同时读）。失败重试、超时在"调度"页配置，这里不重复。行数校验默认开启并自动生成（7.6）。连接器的高级参数折叠在这里 |
| ⑤ 预检查与预览 | 自动执行预检查（错误不能保存，警告可以保存），只读展示生成的 HOCON，确认后保存为新版本 |

**生成规则**：每张表生成 `table_path`；有字段裁剪、过滤条件或增量条件时，再生成该表自己的 `query`（不用全局 `where_condition`，因为它对所有表生效）。sink 端用 SeaTunnel 的占位符 `${table_name}`、`${primary_key}`，console 的变量检查会忽略这些占位符，它们也不能用作参数名。

**预检查**：连通性；源库、表、字段、增量字段是否存在，增量字段是否为时间类型；没有主键的表；目标库是否存在；目标表或索引是否已存在、字段是否兼容；ES 索引名是否全为小写；"增量 + 清空后写入"这类互相冲突的组合。

### 7.3 链路形态与脚本模式

- P1 只支持线性链路：一个 Source、零到多个 Transform、一个 Sink。多表同步通过 Source 自身的多表配置项实现
- 新建任务时选"向导"或"脚本"。向导任务每次保存同时存表单（`job_version.form_json`）和生成的 HOCON，重新编辑时回到向导并带上上次的选择
- **脚本模式**：可以直接编辑 HOCON，照样能引用 `datasource_id` 和变量。**只能从向导转到脚本，不能再转回向导**，转换前二次确认

### 7.4 目标端选项（每个任务单独配置，对所有表生效）

| 选项 | 可选值 | 默认值 |
|---|---|---|
| 目标表或索引不存在时 | 自动创建（`schema_save_mode = CREATE_SCHEMA_WHEN_NOT_EXIST`）/ 报错（`ERROR_WHEN_SCHEMA_NOT_EXIST`） | 自动创建 |
| 写入模式 | 按主键 upsert / 追加 | 所选表都有主键时用 upsert，否则追加 |
| 已有数据 | 保留（`APPEND_DATA`）/ 清空后写入（`DROP_DATA`，**醒目提示**） | 有主键时保留；没有主键且是全量同步时，默认清空后写入，避免每次运行都重复写入一遍 |

- 增量同步时不允许"清空后写入"
- ES 目标：索引名必须小写；upsert 时用 `primary_keys = ["${primary_key}"]` 按各表主键生成 `_id`，追加时由 ES 自动生成 `_id`；索引不存在时自动创建，字段映射由 SeaTunnel 推断

### 7.5 离线增量

增量字段只能是时间类型（date、datetime、timestamp）。时间窗口可选"按天"（默认）、"按小时"，或者"自定义条件"（自己写 where，可以用变量，用于数字型自增 ID 等其他情况）。按天生成：

```sql
updated_at >= '$[yyyy-MM-dd-1] 00:00:00' and updated_at < '$[yyyy-MM-dd] 00:00:00'
```

按小时生成 `>= '$[yyyy-MM-dd HH-1/24]:00:00' and < '$[yyyy-MM-dd HH]:00:00'`。多表时可以给所有表设一个同名增量字段，单张表也可以单独指定。补数和重跑会按各自的计划时间算出正确的时间窗口。

### 7.6 向导任务的行数校验

向导知道源表、目标表、过滤条件和增量窗口，所以会自动生成**按表**的计数校验：

- 全量 + 清空后写入：源端按过滤条件计数，目标端统计整张表
- 增量：两端都按同一个增量字段和同一个时间窗口计数（增量字段被裁掉时，这张表不做校验）
- 全量 + 保留已有数据：目标表里可能还有源端已删除的数据，计数不准，**默认关闭并说明原因**
- ES 目标：只在"全量 + 清空后写入"时校验（`_count`），增量窗口的日期格式在 ES 里不好保证一致，P1 不做

### 7.7 版本管理

- 每次保存都生成一个不可变的新版本
- 支持查看版本差异和回滚（2026-10-24 实现）：版本页选任意两个版本对比配置和任务参数的行级差异；回滚是把旧版本的内容另存为一个新版本并设为当前版本，历史只增不改。向导任务回滚时用旧版本的表单重新生成配置并重新预检查
- 运行实例绑定它所用的版本；实时任务（P2）编辑后提示"新版本待重启生效"

## 8. 变量

照搬 DolphinScheduler 的写法，方便以后迁移。

- **内置变量**：`${system.biz.date}`（计划时间的前一天，yyyyMMdd）、`${system.biz.curdate}`、`${system.datetime}`，以及实例 ID、任务名等
- **时间表达式**：`$[yyyyMMdd-1]`、`$[HHmmss-1/24]`、`$[add_months(yyyyMMdd,-1)]`、`$[this_day(...)]`、`$[last_day(...)]`、`$[month_first_day(...,N)]`、`$[month_last_day(...,N)]`、`$[week_first_day(...,N)]`、`$[week_last_day(...,N)]`、`$[year_week(...)]`
- **优先级**（从高到低）：启动参数（手动运行或补数时填写）> 任务参数 > 项目参数 > 内置参数。没有"上游传参"，因为没有 DAG
- **时间基准**：XXL-JOB 的**计划触发时间**，不是实际运行时间，保证重跑结果一致
- **和 HOCON 的冲突**：HOCON 自己也用 `${}`。console **只替换它认识的变量名**，不认识的原样保留；`$[...]` 不存在冲突
- 实例上记录最终变量值和渲染后（打码）的配置

实现细节（2026-10-24 实现并实测）：

- **替换是文本级的**，在解析 HOCON 之前进行，所以 `query = "... where dt = '${dt}'"` 这种写在引号里的引用也会被替换（HOCON 自己不会替换引号里的 `${}`）。值原样插入，不做转义
- **内置变量**：`system.biz.date`（计划时间前一天，yyyyMMdd）、`system.biz.curdate`（计划时间，yyyyMMdd）、`system.datetime`（计划时间，yyyyMMddHHmmss）、`system.task.instance.id`、`system.task.definition.name`、`system.project.name`
- **时间表达式**：`$[格式±N]` 中 N 是天数，可以写 `7*1`、`1/24`（1 小时）、`1/24/60`（1 分钟）；格式用 Java 日期格式。函数：`add_months(fmt,N)`、`this_day(fmt)`、`last_day(fmt)`（前一天）、`month_first_day/month_last_day(fmt,N)`（N 个月后的月初/月末）、`week_first_day/week_last_day(fmt,N)`（N 周后的周一/周日）、`year_week(fmt[,D])`（周所在的年和周数，D 为一周的第一天，1=周一，默认 1；格式含 `-` 时输出 `2026-43`，否则 `202643`）
- **参数名**只能是字母、数字、`_`、`.`，以字母或 `_` 开头；`system.` 前缀保留。参数值里可以用内置变量和 `$[...]`，**不能引用其他用户参数**
- 不认识的 `${name}` 原样保留并在运行时间线里记一条 WARN（多半是拼写错误），`${?x}` 永远不动。编辑器里的"预览渲染结果"会列出这些引用
- **已知冲突**：配置里任何 `$[...]` 都会被当作时间表达式。如果某个 connector 的选项本身要写 `$[`（比如 JSONPath 的方括号写法），保存时会报错，到时再加转义
- 保存版本时会用当前时间渲染一遍，再检查 HOCON 能否解析、数据源引用是否有效
- `job_instance.variables_json`：运行开始前只保存启动参数，开始后改为保存所有变量的最终值。手动运行可以指定计划时间，默认是当前时间

## 9. 运行、重跑与补数

| 操作 | 计划时间 | 版本 |
|---|---|---|
| 定时调度 | XXL-JOB 的触发时间 | 当前最新版本 |
| 手动运行 | 由用户指定，默认是当前时间 | 当前最新版本 |
| 重跑 | 沿用原实例的计划时间 | **默认用原版本**，可勾选"使用最新版本" |
| 补数 | 日期范围内的每一天 | 默认用最新版本 |

- 补数：选择日期范围，每个计划时间生成一个实例，通过 XXL-JOB 的 trigger OpenAPI 触发，并在参数里带上计划时间
- 上一次还没跑完时如何处理，交给 XXL-JOB 的阻塞策略（单机串行或丢弃后续调度）

实现细节（2026-10-24 实现并实测）：

- **重跑**：只能重跑已结束的实例。沿用原实例的计划时间和启动参数（`job_instance.startup_params_json`），变量按计划时间重新计算；默认用原版本，可选当前版本。新实例的 `trigger_type = RERUN`，`rerun_of_instance_id` 指向原实例。不做失败重试
- **补数的计划时间**：选的是计划时间的日期范围（两端都包含）。任务有调度时，按调度的 cron 生成范围内每个触发时间（和 DolphinScheduler 的补数一致）；没有调度时每天 00:00:00 一次。最多 1000 次。cron 在创建补数时快照到 `backfill_batch.cron`
- **补数的版本**：创建补数时的当前版本，整批都用它
- **补数逐个执行，不支持并发**：XXL-JOB 执行器对同一个任务只有一个线程，并发触发会排队；如果阻塞策略是"丢弃本次"或"停止上次"，还会被丢弃或互相杀掉。所以由 console 控制节奏：后台驱动每 5 秒检查一次，**只有该任务没有任何 PENDING 或 RUNNING 的实例时**（不论是谁触发的），才触发下一个计划时间。`parallelism` 固定为 1
- **失败不中断补数**，结束后可以单独重跑失败的那天。失败重试次数沿用调度配置（定时运行和补数生效，手动运行和重跑不重试）
- **暂停与继续**：XXL-JOB 接不了任务（比如 admin 不可用）时，这次运行记为 FAILED，补数改为 PAUSED 并记录原因；点"继续"后从这个计划时间重试。游标 `backfill_batch.next_index` 先用条件更新占位再触发，触发失败时退回
- **取消**：补数改为 CANCELED，正在运行的那次一起停止
- 补数进行中，定时调度仍会照常触发；它和补数的运行在 XXL-JOB 里按阻塞策略排队

## 10. 监控与数据校验

### 10.1 状态与指标

- 离线：由 XXL-JOB 处理器轮询，直到任务结束（第 5.2 节）
- 实时（P2）：console 定时任务每 10 秒同步一次状态，每 60 秒保存一次指标快照
- 轮询任务预留数据库锁，便于以后扩成多实例
- 日志：任务运行中通过 `/logs/{jobId}` 实时查看；结束后把最后 N MB 归档

### 10.2 运行实例详情页

| 区块 | 内容 |
|---|---|
| 概览 | 状态、计划时间、实际开始和结束时间、耗时、版本、最终变量值、读写行数、QPS、字节数、校验结果 |
| 事件时间线 | 提交 → 运行中 → 完成或失败（附错误信息），重试也记录在这里 |
| 指标 | P1 只显示最终的统计数字；P2 加上 RPS 和延迟的趋势图 |
| 日志 | 实时查看，加上归档日志 |

### 10.3 行数校验（P1）

- 离线实例跑完后自动执行：源端用和这次同步相同的 where 条件执行 `count(*)`，目标端用对应的条件统计（ES 用 `_count`）
- 结果为"通过"或"差值 N"，显示在实例上
- 局限：目标端只有在"清空后写入"或者能按增量字段过滤的时候才准确，其他情况下只能作为参考
- 逐行对比放到后续阶段

实现细节（2026-10-24 实现并实测）：

- 校验配置跟着版本走（`job_version.validation_json`，V4），在脚本编辑器下方配置，保存版本时检查配置是否可用
- **源端**："自动"从唯一的 Jdbc source 推导：有 `query` 时执行 `select count(*) from (query) t_count`，否则统计 `table_path`，两种情况都再加上 `where_condition`；也可以写自定义计数 SQL
- **目标端**：脚本模式下推导不出这次写入对应的过滤条件，所以由用户写计数 SQL，或者填 ES 索引和可选的 query（`POST /<index>/_count`）
- 计数 SQL、ES 索引和 query 都可以使用变量，和这次运行的取值相同
- 计数由 console 直接连数据源执行（只读连接，查询超时默认 10 分钟，`console.validation.timeout`），不依赖引擎上报的指标
- 结果：一致 PASSED、不一致 MISMATCH（显示差值）、计数失败 ERROR，没配置时为 SKIPPED。默认只做报告，不影响运行状态；勾选"不一致时把运行标记为失败"后，MISMATCH 会让运行失败（不会触发重试）。ERROR 永远不让运行失败
- 编辑器里的"测试校验"按当前时间立即执行一次计数，用来调试 SQL
- 校验用到的数据源也记录在 `job_version_datasource` 里，被引用时不能删除

### 10.4 告警

P1 不做告警，只在任务和实例上显示成功、失败等状态。P3 由 console 统一做告警，通过 Webhook 发出，不使用 XXL-JOB 的邮件告警。

## 11. 部署

- console 单实例部署，以 Docker 镜像交付
- docker-compose 里包含 console、MySQL（console 的元数据库）、xxl-job-admin（≥ 3.5.0）
- **不包含 SeaTunnel 集群**，集群按 4.3 的前提条件另行部署

## 12. 分阶段规划

### P1 离线

- 账号、角色、项目、项目参数
- 数据源中心：MySQL、PG、ES
- 元数据导入；场景化向导、高级参数、脚本模式
- 离线同步：MySQL/PG → MySQL/PG/ES，全量和增量
- 选表支持正则检索；字段可以去掉，结构只读预览
- 版本管理；变量；XXL-JOB 调度；手动运行、重跑、补数
- 运行实例详情页；行数校验；集群健康检查
- Docker 交付

### P2 实时

- MySQL-CDC、Postgres-CDC → MySQL/PG/ES
- 前置条件检查：MySQL 要开启 binlog 且格式为 ROW；PG 要设置 `wal_level=logical` 并开放复制权限
- 实时任务操作约定：
  - 停止：默认 savepoint 停止，可以选强制停止
  - 启动：有 savepoint 时默认从它恢复（`restoreMode=SAVEPOINT`），也可以从最近的 checkpoint 恢复，或全新启动
  - 失败后自动重启：按任务开启，从最近的 checkpoint 恢复，最多 N 次，**默认关闭**
  - 编辑正在运行的任务后，提示"新版本待重启生效"
- 正则规则动态选表（运行时匹配，新表自动纳入）
- RPS 和延迟的趋势图

### P3 扩展

- Flink 和 Spark 引擎（EngineClient 的新实现）
- 告警（Webhook）
- 更多数据源；ES 作为来源
- 修改字段名和字段类型
- 类似 Tapdata Board 的拖拽快速创建
- 可选：AES ConfigShade 加固；逐行数据校验；console 多实例部署

## 13. 决策记录

| # | 决策 | 没采用的方案及原因 |
|---|---|---|
| 1 | 参考 seatunnel-web 自建 | fork：版本卡在 2.3.11，代码偏重 |
| 2 | Zeta + REST API | shell 命令：管不了实时任务；Java 客户端：和 SeaTunnel 版本强耦合 |
| 3 | XXL-JOB ≥ 3.5.0 | DolphinScheduler：太重，界面不方便，没有 DAG 需求；内置 Quartz：要重复造轮子 |
| 4 | console 在提交时渲染连接信息 | Metadata SPI 插件：链路复杂，只支持 Zeta |
| 5 | 官方元数据导出 + 手工设计的场景化向导 | 自己写 ClassLoader 扫描 jar：复杂；直接用元数据渲染表单：新手看不懂 |
| 6 | 变量照搬 DolphinScheduler | 自创写法：没有必要 |
| 7 | 分阶段：离线 → 实时 → 扩展 | — |

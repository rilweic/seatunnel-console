# seatunnel-console 表结构（P1）

> 状态：已确认（2026-10-23）
> 建表脚本：`console-server/src/main/resources/db/migration/V1__init.sql`，由 Flyway 在启动时执行
> 总体设计见 [design.md](design.md)

## 1. 通用约定

| 约定 | 说明 |
|---|---|
| 主键 | **UUID v7**，存为带横线的 `CHAR(36)`，由 console 在代码里生成，不用数据库自增 |
| 外部系统的 id | 保持原样：`engine_job_id` 是 `VARCHAR(64)`（Zeta 返回字符串），`xxl_job_id` 是 `INT`，`xxl_log_id` 是 `BIGINT` |
| 时间 | `DATETIME(3)`，使用服务器时区（Asia/Shanghai） |
| 软删除 | `project`、`datasource`、`job_definition` 用 `deleted_at` 标记删除；生成列 `live_name` 保证**只在未删除的数据中**名称唯一，删除后可以重新使用同一个名字 |
| 不可修改的数据 | `job_version` 只插入，不更新，所以没有 `updated_at` |
| 敏感信息 | 只有 `datasource.secret_cipher` 存放凭据，用 AES-GCM 加密，密钥由环境变量 `CONSOLE_SECRET_KEY` 提供；登录密码用 BCrypt 哈希 |

### 为什么用 UUID v7，不用雪花 ID

- 多实例部署时不需要协调。雪花 ID 要给每个实例分配唯一的机器号，在容器里有撞号风险，还怕时钟回拨。
- 前端本来就要把 64 位 ID 当字符串处理（会超出 JavaScript 的精确范围），雪花 ID 的"整数"优势在这里用不上。
- 按时间有序，写入不会打散 InnoDB 索引；普通的 UUID v4 会。
- 代价是每个主键 36 字节，雪花 ID 是 8 字节。按 P1 的数据量可以忽略。

## 2. 表关系

```
sys_user

project ─┬─< project_param
         └─< job_definition ─┬─< job_version ─< job_version_datasource >─ datasource
                             ├── job_schedule (1:1, 只有离线任务有)
                             ├─< backfill_batch
                             └─< job_instance ─┬─< job_instance_attempt   (每次提交到引擎一条)
                                               ├─< job_instance_event     (时间线)
                                               └─< job_metric_sample      (运行中每次轮询一条)
```

外键只在逻辑上存在，**不建数据库外键约束**，由代码保证一致性，这样软删除和数据归档更方便。

## 3. 各表说明

| 表 | 说明 |
|---|---|
| `sys_user` | 角色为 ADMIN 或 USER。账号只停用，不删除。初始管理员在第一次启动时，根据 `CONSOLE_ADMIN_USERNAME` 和 `CONSOLE_ADMIN_PASSWORD` 创建 |
| `project` | 给任务分组，不做权限隔离 |
| `project_param` | 项目参数，变量优先级为：启动参数 > 任务参数 > 项目参数 > 内置参数 |
| `datasource` | `config_json` 存放不敏感的配置（host、port、database 等），`secret_cipher` 存放加密后的敏感字段。**如果仍被某个任务的当前版本引用，就不允许删除**，通过 `job_version_datasource` 判断 |
| `job_definition` | 任务的身份信息：名称、类型（BATCH / STREAMING）、编辑模式（FORM / SCRIPT）。`current_version_id` 指向当前版本。`watermark` 是连续增量的水位（上一个成功窗口的终点，null = 从最早开始），`window_lag_seconds` 是窗口终点比计划时间早多少秒（V10，design.md §7.5）；`watermark_gen` 每次手动调整水位加 1，运行开始时记下，推进水位时对不上就不推进（V13） |
| `job_version` | 每次保存新增一行。`hocon_template` 里只有 `datasource_id`、`encrypt_key_id` 和变量，**不含密码和密钥**。`params_json` 是任务参数，也跟着版本走。向导任务的 `form_json` 里还有表 / 字段映射和字段处理（design.md §7.8、§7.10） |
| `job_version_datasource` | 某个版本引用了哪些数据源，用来判断数据源能不能删除 |
| `job_schedule` | 调度配置，console 是唯一权威，XXL-JOB 只是同步过去的副本。`sync_status` 记录同步结果（PENDING / SYNCED / FAILED），出错时把原因写在 `sync_error` |
| `backfill_batch` | 一次补数请求，它生成的运行实例通过 `job_instance.backfill_batch_id` 关联 |
| `job_instance` | 一次**逻辑上**的运行，汇总状态、行数、错误信息、变量的最终取值、打码后的配置和校验结果。`window_start / window_end` 是这次运行读的同步窗口（`window_end` 为 null 表示不用窗口；`window_start` 为 null 表示从最早开始），重跑原样沿用（V10） |
| `job_instance_attempt` | 每**提交一次**到引擎就记一条，重试时会产生新的一条。每次尝试都有自己的 `engine_job_id` 和 **SeaTunnel 作业日志**归档 |
| `job_instance_event` | 运行实例详情页的时间线 |
| `job_metric_sample` | 运行中每次轮询记一行引擎累计计数（读写行数、字节数），用来画吞吐量曲线（design.md §10.4）。最新的完整指标快照在 `job_instance_attempt.metrics_json` |
| `encrypt_key` | 字段加密的密钥（design.md §7.11）。`key_cipher` 是用 `CONSOLE_SECRET_KEY` 加密后的 Base64 密钥，接口从不返回密钥本身；任务配置里只写 `encrypt_key_id`，提交运行时才在内存里换成密钥。只停用，不删除 |
| `job_stream` | 实时任务（`job_type = STREAMING`）自己的设置和运行状态，和 `job_definition` 一对一（V11，design.md §12 P2）：失败自动重启的设置；源库上的复制身份（MySQL-CDC 的 server-id 段，每个任务 8 个，`server_id_start` 起；PG 的复制槽和 publication 名 `replication_name`）；下次启动能从哪里恢复（`restore_engine_job_id`、`restore_kind` 为 SAVEPOINT 或 CHECKPOINT、当时的版本 `restore_version_id`，以及恢复必然失败时的原因 `restore_blocked`）；监控最近看到的情况（当前运行 `current_instance_id`、最近读到数据的时间、最近一次 checkpoint 和异常、引擎自动恢复次数）。实时任务一次"启动 → 停止或失败"是一条 `job_instance`（`trigger_type = START`），其中每次自动重启是一条 attempt |
| `stream_table_schema` | 实时任务每张表、每一端一行：`columns_json` 是最近一次看到的结构，运行中定时和实际结构对比、发现表结构变更（V11）；`start_columns_json` 是最近一次启动时的结构，详情页按它列出启动以来每张表的变化（V12）。删除任务时一起删掉 |

## 4. 运行实例

### 4.1 由谁创建

| 触发方式 | 创建者 | 说明 |
|---|---|---|
| 定时触发（SCHEDULE） | XXL-JOB 处理器，在收到触发时创建 | 执行参数里没有 `instanceId` |
| 手动运行（MANUAL）、重跑（RERUN）、补数（BACKFILL） | console，在用户操作时先创建，状态为 PENDING | 把 `instanceId` 写进执行参数，再调用 XXL-JOB 的 triggerJob。如果 XXL-JOB 没触发成功，console 把实例标记为 FAILED，并记录原因 |

执行参数：`{"jobDefinitionId": "...", "instanceId": "...", "scheduledTime": "2026-10-22T02:00:00", "params": {...}}`。其中 `instanceId`、`scheduledTime`、`params` 只在手动类触发时才有。

### 4.2 状态与重试

```
PENDING ──提交──> RUNNING ──┬── SUCCESS
                            ├── FAILED    （重试次数用完；或 console 失去对作业的掌握，此时不重试）
                            └── CANCELED  （超时、XXL-JOB 终止或在 console 上停止）
              ↑    │ 失败且还有重试次数
              └────┘ 新增一条 attempt，attempt_count + 1，记录 RETRY 事件
```

- PENDING 超过 `console.run.pending-grace`（默认 10 分钟）仍没开始的实例记为 FAILED，但只在这段时间里该任务**没有别的运行在进行**时：排在另一次运行后面的（XXL-JOB 对同一个任务逐个执行）不算卡住
- **重试由 console 处理器在同一个实例内完成**，XXL-JOB 的失败重试次数固定为 0。原因是 XXL-JOB 每次重试都会生成一条新的调度日志，处理器无法把它和上一次失败关联起来，同一次运行会被拆成多条互不相关的记录。
- 次数和间隔由 `job_schedule.fail_retry_count` 和 `retry_interval_seconds` 控制。
- 超时和"上一次还没跑完怎么办"仍然交给 XXL-JOB 处理。
- `startup_params_json` 只保存启动参数，重跑时沿用；`variables_json` 运行开始后改为所有变量的最终值（V3）。
- 补数：`backfill_batch` 记录日期范围、cron 快照、版本、启动参数和游标 `next_index`，状态为 RUNNING / PAUSED / FINISHED / CANCELED（V3）。详见 design.md §9。
- 停止：PENDING 的实例直接改为 CANCELED（`WHERE status = 'PENDING'` 条件更新）；执行线程领取实例时同样用条件更新从 PENDING 改为 RUNNING，两者不会互相覆盖。RUNNING 的实例记一条 `CANCEL_REQUESTED` 事件，由执行线程停止 Zeta 任务后结束。

## 5. 日志

| 日志 | 存放位置 | 查看方式 |
|---|---|---|
| 运行事件 | `job_instance_event` | console 详情页的时间线；存在数据库里，多实例部署也能看到 |
| **SeaTunnel 作业日志**（最重要） | 各个 Zeta 节点上的 `job-<jobId>.log`，**1 天后**自动删除（`history-job-expire-minutes`） | **运行中，以及结束后 1 天以内**：console 调 `/logs/{jobId}` 实时读取，它会汇总所有节点的日志。**每次尝试结束后**：处理器拉取所有节点的日志，通过 `LogStorage` 归档，再把位置写进 `job_instance_attempt.log_*` |
| XXL-JOB 调度日志 | 执行这次任务的 console 实例的本地磁盘 | 只给管理员看，在 xxl-job-admin 里查看；console 只记录 `xxl_log_id` |

`LogStorage` 接口：
- **P1**：存在本地磁盘，路径为 `${CONSOLE_DATA_DIR:-./data}/instance-logs/<yyyy-MM-dd>/<attemptId>.log`。每份最多 64MB，超过时只保留末尾部分，并设置 `log_truncated = 1`。默认保留 30 天，到期由 console 自动删除。用 Docker 部署时，`./data` 必须挂载为数据卷。
- **多实例部署时**：换成 S3 兼容的对象存储（MinIO 或 OSS），表结构不需要改。

## 6. 以后的变更

- 表结构的变更一律新增 `V<n>__<说明>.sql`，**不修改已经执行过的脚本**，否则 Flyway 的校验和对不上。
- 实时任务（P2）需要增加的字段，比如 savepoint、自动重启次数，在 P2 时通过新的迁移脚本加入。

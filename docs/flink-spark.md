# Flink / Spark 引擎方案（P3，2026-10-28 草案）

本文是 design.md §4.2、§12 P3「Flink 和 Spark 引擎」的展开：SeaTunnel 跑在 Flink / Spark 上要什么前提、和 Zeta 差在哪、console 要怎么接、本地怎么搭测试环境。**还没有讨论定稿，也没有写代码**；动手前按惯例先过一轮问题（文末「待讨论」）。

结论先行：Flink / Spark 只在**已经有在用的 Flink / Spark 集群**、或 Zeta 确实满足不了时才值得接。接入的代价是 console 要带上 SeaTunnel 发行包和引擎客户端、按引擎各写一套提交和监控，Spark 上还没有实时任务。

## 1. SeaTunnel 跑在 Flink / Spark 上的前提

两者做法相同（源码 `seatunnel-core/seatunnel-flink-starter`、`seatunnel-spark-starter`，文档 `docs/en/engines/flink.md`、`spark.md`）：

- 提交作业的机器上有一份**完整的 SeaTunnel 发行包**（`connectors/`、`lib/`、`plugins/`、`starter/`），`config/seatunnel-env.sh` 里 `FLINK_HOME` / `SPARK_HOME` 指向本机的 Flink / Spark 客户端（默认 `/opt/flink`、`/opt/spark`）
- 用 SeaTunnel 的启动脚本提交。脚本只是拼命令：
  - Flink：`${FLINK_HOME}/bin/flink run [--target ...] -c org.apache.seatunnel.core.starter.flink.SeaTunnelFlink <starter.jar> --config <文件> --name <作业名>`，`run-application` 也支持
  - Spark：`${SPARK_HOME}/bin/spark-submit ...`，`--master yarn|local[n]|spark://...`、`--deploy-mode client|cluster`
- 作业配置还是同一套 HOCON。引擎专用设置写在 `env` 里：Flink 用 `flink.` 前缀（只支持整数、布尔、字符串、时长），Spark 用 `spark.` 前缀
- **连接器 jar 按本地路径传**：Flink 把用到的连接器以 `file://` 路径加进 `pipeline.jars` 和 `pipeline.classpaths`（`AbstractFlinkRuntimeEnvironment`），taskmanager 要能按**同一路径**找到这些 jar。所以发行包要放在 jobmanager、taskmanager、提交端都一样的路径下（共享挂载）
- Flink 启动脚本第一次运行时把 `connectors`、`lib`、`plugins` 打成 `runtime.tar.gz` 写回发行包目录（给 YARN application 模式用）。全量连接器有 2.9G，所以给引擎用的发行包应只放需要的连接器（见 §5.1）

### 1.1 版本对应

| 引擎版本 | 启动脚本 | 编译时依赖的版本 | SeaTunnel e2e 是否覆盖 |
|---|---|---|---|
| Flink 1.12–1.14 | `start-seatunnel-flink-13-connector-v2.sh` | 1.13 | 1.13、1.14 |
| Flink 1.15–1.18 | `start-seatunnel-flink-15-connector-v2.sh` | 1.15.3 | 1.15–1.18 |
| **Flink 1.19** | 无 | — | **不覆盖，文档也没提**，不建议用 |
| Flink 1.20 | `start-seatunnel-flink-20-connector-v2.sh` | 1.20.1 | 1.20 |
| Spark 2.4 | `start-seatunnel-spark-2-connector-v2.sh` | 2.4.0 | 是 |
| Spark 3.x | `start-seatunnel-spark-3-connector-v2.sh` | 3.3.0 | 3.3.0 |

新搭环境用 **Flink 1.20.1**、**Spark 3.3.x**。

## 2. 和 Zeta 的能力差别

按 SeaTunnel 文档 `docs/en/engines/overview.md` 的对比：

| | Zeta | Flink | Spark |
|---|---|---|---|
| 离线 | ✅ | ✅ | ✅ |
| CDC 实时同步 | ✅ | ✅ | ❌ |
| 表结构变更同步 | ✅ | ✅ | ❌ |
| SeaTunnel REST API（提交、查询、日志） | ✅ | ❌ | ❌ |

- **Spark 只能跑离线任务**，P2 的实时同步整块不可用
- Flink 能跑实时，但 P2 在 Zeta 上的实测结论（§12「P2 实测结论」：各类 DDL、transform 的表现，savepoint / checkpoint 恢复）都要在 Flink 上重测
- SeaTunnel 文档明确说 REST API 只有 Zeta 有，Flink / Spark 上要用引擎自己的工具（Flink CLI / REST、spark-submit / History Server）

## 3. console 要改什么

### 3.1 打破的约束

现在的硬约束是"console 只通过 Zeta REST API v2 和引擎交互，不依赖 SeaTunnel jar"（CLAUDE.md）。Flink / Spark 没有这条路：

- console 所在机器（或专门的提交节点）要有 **SeaTunnel 发行包 + Flink / Spark 客户端**，console 调命令行提交。不需要把 SeaTunnel jar 放进 console 进程里，但部署上 console 不再是一个干净的 Docker 镜像
- 渲染出的配置（带解密后的密码）要先**写成文件**才能交给启动脚本。要放在只有提交进程能读的临时目录、提交后立刻删掉，这和"密码只在内存里"的现状不同，要单独说明

### 3.2 按引擎实现的部分（`EngineClient` 的新实现）

| 能力 | Zeta（现在） | Flink | Spark |
|---|---|---|---|
| 提交 | `POST /submit-job`，console 指定 jobId | `flink run`（命令行）；作业名写 console 实例 id，用 Flink REST `/jobs/overview` 按名字找回 jobId | `spark-submit`；YARN 上拿 applicationId |
| "不重复提交"（CLAUDE.md） | 同一 jobId Zeta 不会再跑 | 按作业名查重；能否固定 Flink jobId（`$internal.pipeline.job-id`）待实测 | 按 YARN 应用名查重 |
| 状态 | `/job-info/{id}` | Flink REST `/jobs/{id}` | YARN RM REST / Spark History Server；local 模式只能看进程 |
| 停止 / savepoint | `/stop-job` | `/jobs/{id}/stop`（带 savepoint）、`/jobs/{id}`（PATCH 取消） | YARN kill；没有 savepoint |
| 从 savepoint / checkpoint 恢复 | `restoreMode` 参数 | `flink run -s <路径>`，路径 console 要记下来 | 不支持 |
| 指标 | `/job-info` 里的计数 | Flink REST 的 vertex 指标；SeaTunnel 的计数是否以 Flink 指标暴露待实测 | Spark 指标 / 只在结束时有日志里的统计 |
| 日志 | `/logs/{jobId}` 按作业分文件 | jobmanager / taskmanager 日志不按作业分开，只能截取或整份存 | YARN 日志聚合；local 模式是提交进程的输出 |
| 连接器参数定义 | `/option-rules`（向导高级选项、脚本模板） | 没有，向导要退回 console 内置的说明或固定用 Zeta 集群读 | 同 Flink |
| 集群健康检查 | `/overview` 等 | Flink REST `/overview`、`/taskmanagers` | YARN / Spark Master REST |

### 3.3 其他要定的

- **任务归属哪个引擎**：每个任务选引擎，还是整个 console 只配一种。建议按"集群"配置，一个 console 可以登记多个集群（Zeta / Flink / Spark），任务选集群
- **向导**：生成的 HOCON 大体通用；`env` 里 Zeta 专用的设置（如 `checkpoint.retain-after-job-cancelled`、`checkpoint.interval` 的写法）要按引擎换。Spark 集群上实时同步入口直接不可选
- **离线调度**：XXL-JOB 执行器线程里跑命令行、等进程结束，和现在轮询 Zeta 的结构不同；`flink run` 默认附着等待（离线）还是 `-d` 分离（实时）要分开处理

## 4. 实现顺序建议（定稿前仅供讨论）

1. 先**只做 Flink**，因为它离线、实时都能跑，用户最可能已有 Flink 集群；Spark 等真有需求再做
2. 先离线：提交、状态、停止、日志、计数，跑通 XXL-JOB 调度
3. 再实时：savepoint 停止和恢复、checkpoint 恢复、自动重启，按 §12 的实测表在 Flink 上重测 DDL 和 transform
4. 每一步都用 §5 的本地环境实测

## 5. 本地测试环境（Docker 单节点）

可行。SeaTunnel 自己的 e2e 就是这么做的（`seatunnel-e2e-common` 的 `AbstractTestFlinkContainer` / `AbstractTestSparkContainer`）：起单节点容器，把发行包拷进去，在容器里执行启动脚本。

### 5.1 准备精简的发行包

不动 Zeta 正在用的发行包，另复制一份只带需要的连接器：

```bash
SRC=~/software/apache-seatunnel-3.0.1-SNAPSHOT
DST=~/software/seatunnel-engines
rsync -a --exclude connectors --exclude logs "$SRC/" "$DST/"
mkdir -p "$DST/connectors" "$DST/logs"
cp "$SRC/connectors/plugin-mapping.properties" "$DST/connectors/"
for c in jdbc cdc-base cdc-mysql cdc-postgres elasticsearch http-base fake console; do
  cp "$SRC/connectors/connector-$c-3.0.1-SNAPSHOT.jar" "$DST/connectors/"
done
```

### 5.2 端口

只映射看界面用的端口；容器之间的通信端口在 Docker 网络里，不和宿主机冲突。

| 端口 | 用途 | 本机现状 | 映射 |
|---|---|---|---|
| Flink 8081 | Web UI + REST | 被 xxl-job-admin 占用 | **8082:8081** |
| Flink 6123 / 6124 | RPC / blob | 空闲 | 不映射 |
| Spark 8080 / 8081 | Master / Worker UI | 被 Zeta / xxl-job-admin 占用 | local 模式不需要 |
| Spark 7077 | Master | 空闲 | 在容器里提交时不映射 |
| Spark 4040 | 作业运行时 UI | 空闲 | 4040:4040 |

### 5.3 `docker-compose.yml`

```yaml
# Flink 1.20 单节点（jobmanager + taskmanager）和可选的 Spark 3.3 local 模式。
name: seatunnel-engines

x-common: &common
  extra_hosts:
    - "host.docker.internal:host-gateway"   # 容器里访问宿主机上的数据库（Linux 必须加）
  volumes:
    - ${SEATUNNEL_DIST:-~/software/seatunnel-engines}:/opt/seatunnel   # 各容器路径必须一致
    - ./jobs:/opt/jobs                                                  # 作业配置
    - ./flink-state:/opt/flink-state                                    # checkpoint / savepoint

services:
  jobmanager:
    <<: *common
    image: flink:1.20.1-scala_2.12-java11      # 官方镜像，amd64 / arm64
    command: jobmanager
    ports:
      - "8082:8081"
    environment:
      FLINK_PROPERTIES: |
        jobmanager.rpc.address: jobmanager
        rest.bind-address: 0.0.0.0
        state.checkpoints.dir: file:///opt/flink-state/checkpoints
        state.savepoints.dir: file:///opt/flink-state/savepoints
        execution.checkpointing.externalized-checkpoint-retention: RETAIN_ON_CANCELLATION

  taskmanager:
    <<: *common
    image: flink:1.20.1-scala_2.12-java11
    command: taskmanager
    depends_on: [jobmanager]
    environment:
      FLINK_PROPERTIES: |
        jobmanager.rpc.address: jobmanager
        taskmanager.numberOfTaskSlots: 4
        taskmanager.memory.process.size: 2048m
        state.checkpoints.dir: file:///opt/flink-state/checkpoints
        state.savepoints.dir: file:///opt/flink-state/savepoints

  spark:
    <<: *common
    profiles: ["spark"]                        # docker compose --profile spark up -d 才启动
    image: apache/spark:v3.3.2                 # 和 spark-3 starter（3.3.0）同系列，amd64 / arm64
    user: root
    command: ["tail", "-f", "/dev/null"]       # 只当提交环境，作业以 local 模式跑在容器里
    ports:
      - "4040:4040"
```

`seatunnel-env.sh` 默认的 `FLINK_HOME=/opt/flink`、`SPARK_HOME=/opt/spark` 正好是两个官方镜像的安装路径，不用改。

### 5.4 提交

```bash
docker compose up -d                        # Flink
docker compose --profile spark up -d        # 连 Spark 一起

docker compose exec jobmanager \
  /opt/seatunnel/bin/start-seatunnel-flink-20-connector-v2.sh --config /opt/jobs/demo.conf
# http://localhost:8082

docker compose exec spark \
  /opt/seatunnel/bin/start-seatunnel-spark-3-connector-v2.sh \
  --master 'local[4]' --deploy-mode client --config /opt/jobs/demo.conf
```

### 5.5 注意

- 作业配置里的数据库地址写 `host.docker.internal`（或数据库的真实 IP），不能写 `127.0.0.1`；从 console 复制的配置要手动填密码（console 只在提交给 Zeta 时注入）
- 镜像要先拉好：本机 Docker Hub 走代理会失败。`flink:1.20.1-scala_2.12-java11`、`apache/spark:v3.3.2` 都有 arm64；SeaTunnel e2e 用的 `tyrantlucifer/*` 只有单一架构
- 官方 Flink 镜像不带 Hadoop。JDBC、CDC、ES、HTTP 用不到；HDFS、S3 等文件类连接器可能缺类，到时按需补
- 已有的一份 Flink compose（1.19.1、端口 8081、挂载 `/mnt/flink`）的问题：1.19 不在支持范围，改 1.20.1；没配 checkpoint / savepoint 目录，CDC 停止做 savepoint 会失败；在这台 Mac 上 8081 冲突、`/mnt` 不能用；Linux 上访问宿主机数据库要加 `extra_hosts`。两个容器同路径挂载 `/mnt/flink` 的做法是对的，发行包放进去即可

## 6. 待讨论（定稿前的问题）

1. 为什么要 Flink / Spark：已有集群（版本、部署方式：Standalone / YARN / K8s），还是 Zeta 哪里不够
2. 只做 Flink，还是 Flink、Spark 都做
3. 引擎按集群登记、任务选集群（建议），还是全局只配一种
4. 提交端放哪：console 容器里带 SeaTunnel 发行包和 Flink 客户端，还是单独的提交节点（console 通过 SSH / 小代理调用）
5. 配置文件落盘的处理：临时目录、权限、提交后删除，能否接受
6. 向导在 Flink / Spark 集群上的取舍：高级选项和脚本模板没有 `/option-rules` 时怎么办
7. 日志：Flink / Spark 的日志不按作业分开，console 存什么

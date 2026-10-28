# Flink 引擎方案（P3，2026-10-28 草案）

本文是 design.md §4.2、§12 P3「Flink 和 Spark 引擎」的展开：SeaTunnel 跑在 Flink 上要什么前提、官方启动脚本到底做了什么、不同部署方式的提交差别、和 Zeta 差在哪、console 要怎么接、本地怎么搭测试环境。**方向已定（§0），还没有写代码**；§8 是剩下要讨论的问题。

## 0. 已定的方向（2026-10-28）

**运行前提**：console 不部署、不改动引擎集群，只使用已有的。用 Zeta 运行要有一个可访问的 Zeta 集群（现状）；用 Flink 运行要有一个**可访问的 Flink 集群**（Standalone 或 session，§3），console 只需要它的 REST 地址。集群的配置（含 checkpoint / savepoint 目录）由集群维护方负责。

| # | 问题 | 结论 |
|---|---|---|
| 1 | 为什么接 Flink | console 是用户端，要适配不同的引擎、把 SeaTunnel 的能力发挥出来，不只绑定 Zeta |
| 2 | 做哪些引擎 | **只做 Flink**，Spark 不做 |
| 3 | 引擎怎么选 | console 登记多个集群（Zeta / Flink 和各自的地址），**任务选集群**，建好后**不能换集群**（§5.3） |
| 4 | 提交端放哪 | 先用**做法 1**：console 机器带 Flink 客户端和 SeaTunnel 发行包，`flink run` 远程提交；做法 2 的条件见 §5.1 |
| 5 | 配置文件落盘 | 提交用的临时文件（带明文密码）提交后删除；页面看到的、溯源用的是 console 自己存的打码版本，不受影响（§5.7） |
| 6 | 没有 `/option-rules` 怎么办 | 用 console 机器上的发行包离线读取（§5.2） |
| 7 | 日志 | 不归档 Flink 的 jobmanager / taskmanager 日志，页面上按需经 Flink REST 读取；只存 console 自己产生的和按作业的小块内容（§5.8） |
| 8 | checkpoint / savepoint | **用集群的配置**，console 不指定目录（§5.6）；清理靠 Flink 的 CLAIM 恢复模式和 `/savepoint-disposal`（§5.9） |
| 10 | console 的 Docker 镜像带不带 Flink 客户端 | 暂不考虑 |
| 9 | 部署方式 | Standalone / session 下 console 功能一样；application 模式影响"集群地址"（§3）。第一版只支持 Standalone / session |

## 1. 前提

### 1.1 Flink 客户端是什么

"客户端"是**角色**，不是另一个安装包：就是同一个 Flink 发行包（如 `flink-1.20.1-bin-scala_2.12.tgz`），在服务器上用来起 jobmanager / taskmanager，在提交机上只用它的 `bin/flink`、`lib/` 和 `conf/`，不启动任何进程。

`flink run` 时客户端做三件事：

1. 在本地 JVM 里执行用户 jar 的 `main()`（对 SeaTunnel 就是 `SeaTunnelFlink`），生成 JobGraph
2. 把用户 jar（`pipeline.jars`）上传到集群的 BlobServer
3. 把作业交给集群（Standalone 走 REST；YARN / K8s 先通过资源管理器找到或拉起集群）

所以客户端版本要和集群一致：生成 JobGraph 用的 Flink 类必须和集群运行时对得上。

SeaTunnel 在其中的位置：它不是 Flink 的服务端组件，而是一个**被提交上去的普通 Flink 程序**。starter jar 把 SeaTunnel 连接器包装成 Flink 算子、组成 DataStream 作业；运行时 taskmanager 上真正读写数据的仍是 SeaTunnel 连接器的代码。

### 1.2 版本对应

| Flink 版本 | 启动脚本 | 编译时依赖的版本 | SeaTunnel e2e 是否覆盖 |
|---|---|---|---|
| 1.12–1.14 | `start-seatunnel-flink-13-connector-v2.sh` | 1.13 | 1.13、1.14 |
| 1.15–1.18 | `start-seatunnel-flink-15-connector-v2.sh` | 1.15.3 | 1.15–1.18 |
| **1.19** | 无 | — | **不覆盖，文档也没提**，不建议用 |
| 1.20 | `start-seatunnel-flink-20-connector-v2.sh` | 1.20.1 | 1.20 |

新搭环境用 **Flink 1.20.1**。集群是别人的就按它的版本选客户端和启动脚本；1.19 不支持。

### 1.3 提交端要有的

- 和集群同版本的 Flink 客户端
- 一份 SeaTunnel 发行包，只放用到的连接器（全量 2.9G），`lib/` 里补 JDBC 驱动（SeaTunnel 不带 MySQL 驱动）；`config/seatunnel-env.sh` 的 `FLINK_HOME` 指向本机客户端（默认 `/opt/flink`）
- 作业配置写成**本地文件**（`--config` 只认本地路径）

作业配置还是同一套 HOCON，Flink 专用设置写在 `env` 里（映射见 §2.4）。

## 2. 官方启动脚本做了什么

依据：本机 `apache-seatunnel-3.0.1-SNAPSHOT` 的 `bin/start-seatunnel-flink-20-connector-v2.sh` 和反编译的 `starter/seatunnel-flink-20-starter.jar`（2026-10-28）。只验证到"拼出的命令"，没有在 Flink 上实跑。

### 2.1 整体流程

```
start-seatunnel-flink-20-connector-v2.sh --config job.conf ...
 │
 ├─① shell：source seatunnel-env.sh；第一次运行时打 runtime.tar.gz
 ├─② java FlinkStarter：只解析参数、打印一条 flink run 命令，不提交
 ├─③ shell：eval 这条命令
 │
 └─ ${FLINK_HOME}/bin/flink run ... -c SeaTunnelFlink seatunnel-flink-20-starter.jar --config ... --name ...
     │
     └─④ SeaTunnelFlink.main（在 flink run 的 JVM 里，见 §2.5）
          ├ 读 HOCON、替换 -i 变量
          ├ 定位 SEATUNNEL_HOME，收集 lib/、plugins/、connectors/ 的 jar
          ├ env → Flink 配置（并行度、checkpoint、重启策略、flink.* 透传）
          ├ source → transform → sink 搭成 DataStream 作业
          └ env.execute(作业名) → 提交给集群；默认附着等作业结束
```

脚本只是一层壳：**SeaTunnel 自己不调 Flink API 提交**，提交完全靠 Flink 客户端。所以 console 也可以不走脚本，自己拼 `flink run`。

### 2.2 shell 层

- `source config/seatunnel-env.sh`：本机这份只有 `FLINK_HOME=${FLINK_HOME:-/opt/flink}`、`SPARK_HOME` 和 metalake 默认值
- `runtime.tar.gz` 不存在就把 `connectors/`、`lib/`、`plugins/` 打成它，**只有 YARN application 模式用**（`yarn.ship-archives`）。其他模式第一次运行也会打包，嫌慢可以先 `touch` 一个空文件跳过
- 设 log4j 参数（`log4j2.configurationFile`、`seatunnel.logs.path`），只作用于下一步的 `FlinkStarter`
- `FlinkStarter` 退出码 234 = 打印帮助；0 = 把输出最后一行 `eval`

### 2.3 FlinkStarter 拼命令

`AbstractFlinkStarter.buildCommands()` 按顺序拼：

```
${FLINK_HOME}/bin/flink
  run | run-application                     # -e/--deploy-mode，默认 run
  [--target <m>]                            # --master/--target：local、remote、yarn-session、yarn-per-job、
                                            #   yarn-application、kubernetes-session、kubernetes-application
  [-Dyarn.ship-files="<配置文件>"            # 仅 yarn-application
   -Dyarn.ship-archives="<HOME>/runtime.tar.gz"]
  [-Dyarn.application.name=<作业名>]         # 仅 yarn-* 模式
  <不认识的参数原样透传>                      # JCommander acceptUnknownOptions：-m、-s、-p、-D、-d ...
  -c org.apache.seatunnel.core.starter.flink.SeaTunnelFlink
  <HOME>/starter/seatunnel-flink-20-starter.jar
  --config <文件> [--check] --name <作业名> [--encrypt|--decrypt] --deploy-mode <run|run-application>
  [-i k=v ...]
```

透传已实测：`FlinkStarter -m 192.168.1.141:8081 --config x.conf --name demo` 输出

```
${FLINK_HOME}/bin/flink run -m 192.168.1.141:8081 -c org.apache.seatunnel.core.starter.flink.SeaTunnelFlink .../seatunnel-flink-20-starter.jar --config x.conf --name demo --deploy-mode run
```

`-m`（`--jobmanager`）是 **Flink CLI 的参数**，不是 SeaTunnel 的；SeaTunnel 的 `--master` / `--target` 对应 Flink 的 `--target`（部署模式，不是地址）。带了 `--target` 时 Flink 走 Generic CLI，`-m` 可能不生效，地址改用 `-Drest.address=... -Drest.port=...`，或写在客户端 `conf/config.yaml` 的 `rest.address` / `rest.port`（改的是提交端本地的客户端配置，不是集群）。console 按任务所选集群的地址每次带上 `-m`，不依赖客户端配置文件。

### 2.4 SeaTunnelFlink：读配置、加载连接器、搭作业

**读配置**（`FlinkTaskExecuteCommand`）：`--config` 必须是本地文件；`ConfigBuilder` 解析 HOCON 并替换 `-i` 变量；开了 metalake 就合并；`--name` 不是默认的 `SeaTunnel` 时写进 `env.job.name`。

**定位安装目录**（`Common`）：

- `getSeaTunnelHome()`：系统属性 `SEATUNNEL_HOME` → 环境变量 `SEATUNNEL_HOME` → `appRootDir()`
- `appRootDir()`：`run` 模式（或作为 starter 运行时）取 starter jar 所在路径的上两级，即发行包根目录；`run-application` 模式是当前目录下的 `runtime.tar.gz`（YARN 解开 ship 来的包的位置）；`cluster` 模式是当前目录
- `connectors/`、`lib/`、`plugins/` 基于 `getSeaTunnelHome()`；`starter/` 基于 `appRootDir()`，**不看 `SEATUNNEL_HOME`**

**收集 jar**（`FlinkExecution`）：starter jar 本身、`env.jars` 里的第三方 jar、`plugins/` 下各插件的依赖、`lib/` 下的 jar、实际用到的连接器（按 `connectors/plugin-mapping.properties` 查），以 `file://` URL 写进 `env.pipeline.jars` 和 `env.pipeline.classpaths`，**本地不存在的路径会被丢掉**（日志 `Remove invalid jar ...`）；同时加进当前线程的 classloader。

**`env` → Flink 配置**（`AbstractFlinkRuntimeEnvironment`、`EnvironmentUtil`）：

| HOCON `env` | 在 Flink 上 |
|---|---|
| `flink.xxx` | 去掉前缀写进 Flink `Configuration`（`flink.table.exec*` 进 Table 配置），再用它创建执行环境 |
| `parallelism`（旧 `execution.parallelism`） | `setParallelism` |
| `checkpoint.interval`（旧 `execution.checkpoint.interval`） | `enableCheckpointing`；流作业默认 10 秒，批作业未设或 ≤0 时关闭 |
| `checkpoint.timeout`、`checkpoint.min-pause`、`execution.checkpoint.mode`（exactly-once / at-least-once）、`execution.checkpoint.cleanup-mode`、`execution.checkpoint.fail-on-error`、`execution.max-concurrent-checkpoints` | 对应的 `CheckpointConfig` 设置 |
| `execution.checkpoint.data-uri` | 按作业指定 checkpoint 存储位置（console 不用，§5.6） |
| `execution.state.backend = rocksdb` | RocksDB 状态后端 |
| `execution.restart.strategy`（no / fixed-delay / failure-rate）及其参数 | Flink 重启策略 |
| `execution.buffer.timeout`、`execution.max-parallelism`、`execution.time-characteristic` | 对应设置 |
| `job.mode = BATCH / STREAMING` | `RuntimeExecutionMode` |

`execution.*` 旧写法会打一条"请改用 `flink.` 前缀"的废弃警告。

**执行**：打印执行计划后 `env.execute(作业名)`；批作业结束打印一份 `FlinkJobMetricsSummary`（读写条数，只在客户端日志里）。`flink run` 默认附着等作业结束，实时作业要透传 `-d` 才会提交后立即返回。

### 2.5 main 在哪执行、连接器怎么到 taskmanager

| 提交方式 | `SeaTunnelFlink.main` 在哪 |
|---|---|
| `flink run`（`--deploy-mode run`，Standalone / session） | 提交端的 Flink 客户端 JVM |
| `flink run-application`（yarn-application、kubernetes-application） | 集群里的 jobmanager |
| Flink REST `/jars/upload` + `/jars/{id}/run` | jobmanager |

读配置、找安装目录、收集 jar 都发生在 main 所在的机器上。

连接器到 taskmanager 的路径（按 Flink 机制推断，**待验证**）：`pipeline.jars` 是 Flink 的用户 jar，`flink run` 时客户端会上传到 jobmanager 的 BlobServer，taskmanager 从那里下载，不读本地路径；`pipeline.classpaths` 要求各节点本地可访问，但路径不存在时 classloader 只是跳过。所以**用 `flink run` 从集群外提交时，taskmanager 上可能根本不需要 SeaTunnel 发行包**。SeaTunnel 文档和 e2e 的做法是每个节点都放一份，可能只是保险。验证方法见 §7.6；验证不通过时，退路是请集群维护方在各节点同一路径放一份发行包（只放文件，不改 Flink 配置、不重启）。

## 3. 部署方式：Standalone / YARN / K8s

启动脚本是同一个，用 `--target` 和 `--deploy-mode` 区分，最后变成不同的 `flink run`：

| | Standalone | YARN | K8s（原生） |
|---|---|---|---|
| 集群从哪来 | 已经在跑 | session：预先 `yarn-session.sh`；application：每个作业拉起一个 | session：预先拉起；application：每个作业拉起一个 |
| 客户端怎么找到集群 | REST 地址：`-m host:8081` 或客户端 `rest.address` | `HADOOP_CONF_DIR` 找 ResourceManager；session 加 `-Dyarn.application.id=...` | `~/.kube/config` + `-Dkubernetes.cluster-id=...` |
| SeaTunnel 参数 | 不带 `--target`（或 `remote`） | `--target yarn-session`；或 `--target yarn-application --deploy-mode run-application` | `--target kubernetes-session`；或 `--target kubernetes-application --deploy-mode run-application` |
| main 在哪 | 客户端本机 | session：本机；application：YARN 里的 jobmanager | session：本机；application：Pod 里的 jobmanager |
| 连接器怎么到集群 | 随作业上传 BlobServer（待验证，§2.5） | application：SeaTunnel 自动加 `yarn.ship-files`（配置）和 `yarn.ship-archives`（`runtime.tar.gz`），YARN 分发进容器 | application：**SeaTunnel 不管**，要自己做带发行包的镜像，并设 `SEATUNNEL_HOME`（starter 优先读它；推断，未实测） |
| 客户端额外要有 | 只要同版本 Flink | Hadoop 配置和依赖（`HADOOP_CLASSPATH`），Kerberos 还要 keytab | kubeconfig、建 Pod 的权限 |
| 提交后监控 | Flink REST | Flink REST，地址经 YARN 代理 / application 地址拿 | Flink REST，地址来自 K8s Service |

- `yarn-per-job` 从 Flink 1.15 起废弃，新环境用 application 模式
- K8s 还有不经 `flink` 命令的方式：Flink Kubernetes Operator，写一个 `FlinkDeployment` 资源交给 K8s API，同样要带 SeaTunnel 发行包的镜像

**对 console 的影响**：

- **Standalone 和 session（YARN session、K8s session）对 console 的功能没有区别**：集群已经在跑、有固定的 REST 地址，提交后的状态、停止、savepoint、集群健康都走同一套 Flink REST。区别只在提交参数和提交端要准备的环境（Hadoop 配置、kubeconfig），属于集群登记里的几个字段
- **application 模式会影响 console**：每个作业单独拉起一个 Flink 集群，没有固定的"集群地址"——console 要在提交后找回这个作业自己的 REST 地址（YARN 的 application 地址、K8s 的 Service）才能监控和停止；集群健康页也没有一个常驻集群可看。所以第一版只支持 Standalone / session，集群登记的是一个 REST 地址

## 4. 和 Zeta 的能力差别

按 SeaTunnel 文档 `docs/en/engines/overview.md` 的对比（Spark 一列说明为什么不做）：

| | Zeta | Flink | Spark |
|---|---|---|---|
| 离线 | ✅ | ✅ | ✅ |
| CDC 实时同步 | ✅ | ✅ | ❌ |
| 表结构变更同步 | ✅ | ✅ | ❌ |
| SeaTunnel REST API（提交、查询、日志） | ✅ | ❌ | ❌ |

- Flink 能跑实时，但 P2 在 Zeta 上的实测结论（design.md §12「P2 实测结论」：各类 DDL、transform 的表现，savepoint / checkpoint 恢复）都要在 Flink 上重测
- REST API 只有 Zeta 有，Flink 上用 Flink 自己的 CLI 和 REST

和 Zeta 的根本区别在"谁是服务端"：Zeta 是常驻的 SeaTunnel 服务端，发行包只在 Zeta 节点上，console 发 HTTP、配置在请求体里、不落盘；Flink 上没有 SeaTunnel 服务端，得有人在客户端把 SeaTunnel 装进 Flink 作业里，所以提交端要有发行包和 Flink 客户端，配置要落成文件。

## 5. console 要改什么

### 5.1 打破的约束和提交端放哪

现在的硬约束是"console 只通过 Zeta REST API v2 和引擎交互，不依赖 SeaTunnel jar"（CLAUDE.md）。Flink 没有这条路，约束改为：**Zeta 集群只走 REST；Flink 集群走提交端的命令行提交 + Flink REST，console 进程本身仍不依赖 SeaTunnel jar**。

- 提交端要有 **SeaTunnel 发行包 + Flink 客户端**，console 调命令行提交。做法 1 下 console 不再是一个干净的 Docker 镜像（镜像里要带这两样，或挂载进来）
- 渲染出的配置（带解密后的密码）要先**写成文件**，放在只有提交进程能读的临时目录、提交后立刻删掉（§5.7）

前提是**不改用户的 Flink 集群**。提交端可以放在：

| 做法 | console 机器要带什么 | 要动集群吗 | 说明 |
|---|---|---|---|
| **1. console 机器 `flink run` 远程提交（已选）** | 同版本 Flink 客户端 + 发行包 | 不用 | main 在 console 机器上跑，`SEATUNNEL_HOME` 按 jar 位置推算即可 |
| 2. 单独的提交节点 | 无 | 不用 | 和做法 1 一样，只是放在另一台机器，console 远程调用；想让 console 保持干净镜像时用 |
| 3. Flink REST 提交（`/jars/upload` + `/jars/{id}/run`） | 无 | **要** | main 在 jobmanager 上跑：jobmanager 的 JVM 要加 `-DSEATUNNEL_HOME`（否则按上传目录推算，连接器全找不到），`--config` 要从 jobmanager 本地读（配置要放到集群可读的共享位置）。不改集群就走不通，不考虑 |

**做法 2 需要的条件**：

- **提交节点**：和做法 1 的 console 机器要的一样——同版本 Flink 客户端、SeaTunnel 发行包（含 JDBC 驱动）、能访问 Flink 的 REST 地址；一个只有提交用户能读的临时目录
- **console → 提交节点的通道**，二选一：
  - SSH：提交节点上开一个专用账号，console 用密钥登录（私钥加密存进 console，和数据源密码一样）；配置内容通过 SSH 的标准输入写进临时文件，不经过命令行参数（命令行在 `ps` 里可见）；`flink run` 的输出经 SSH 读回来
  - 小代理：提交节点上跑一个很小的 HTTP 服务，console 把配置放在请求体里发过去，代理写文件、执行 `flink run`、返回输出和 jobId；要有鉴权（共享密钥或 mTLS），最好只监听内网
- **超时和失联**：离线作业 `flink run` 会附着等到结束，通道断了不代表作业没了——和 Zeta 的 `ENGINE_RESPONSE_LOST` 一样，要按作业名去 Flink REST 查，不能重新提交。实时作业带 `-d`，提交完就返回
- **并发**：XXL-JOB 多个作业同时触发时，提交节点要能并发跑多个 `flink run`（每个是一个 JVM，内存按并发数估）
- 集群登记里多出"提交节点"的字段（地址、账号或代理地址、凭据），见 §5.3

### 5.2 Flink 的 `EngineClient` 实现

| 能力 | Zeta（现在） | Flink |
|---|---|---|
| 提交 | `POST /submit-job`，console 指定 jobId | `flink run -m <REST 地址>`；`--name` 写 console 实例 id（进 `env.job.name`），用 Flink REST `/jobs/overview` 按名字找回 jobId |
| "不重复提交"（CLAUDE.md） | 同一 jobId Zeta 不会再跑 | 按作业名查重；能否固定 Flink jobId（`$internal.pipeline.job-id`）待实测（§7.6） |
| 状态 | `/job-info/{id}` | Flink REST `/jobs/{id}` |
| 停止 / savepoint | `/stop-job` | `POST /jobs/{id}/stop`（带 savepoint，目录用集群默认）、`PATCH /jobs/{id}`（取消） |
| 从 savepoint / checkpoint 恢复 | `restoreMode` 参数 | `flink run -s <路径>`（透传，§2.3），路径 console 记下来 |
| 指标 | `/job-info` 里的计数 | Flink REST 的 vertex 指标；SeaTunnel 的计数是否以 Flink 指标暴露待实测；批作业结束时 `flink run` 输出里有 `FlinkJobMetricsSummary` |
| 日志 | `/logs/{jobId}` 按作业分文件 | 不按作业分开，见 §5.8 |
| 连接器参数定义 | `/option-rules` | 用 console 机器上的发行包离线读取（见下） |
| 集群健康检查 | `/overview` 等 | Flink REST `/overview`、`/taskmanagers` |

**`/option-rules` 是什么**：Zeta 的 REST 接口 `GET /option-rules?type=source|sink|transform&plugin=<名字>`，返回某个连接器或 transform 的全部选项定义（选项名、类型、默认值、必填 / 可选、说明）。console 用它做两件事：向导里的"高级选项"（列出连接器支持但向导没覆盖的选项，让用户填），和脚本模式里插入 transform 的模板。它读的是 Zeta 节点上实际装的连接器，所以和集群版本一致（`ZetaRestClient`）。

Flink 上没有这个接口，但做法 1 下 console 机器本身就有发行包：SeaTunnel 自带的 `bin/seatunnel-connector.sh -o <名字> -pt source|sink|transform` 离线输出同样的信息（已实测，3.0.1）：

```
Jdbc source
Required Options:
 Key: 'url', default: null (fallback keys: [base-url]), Description: 'url'
 ...
Optional Options:
 Key: 'fetch_size', default: 0 (fallback keys: []), Description: '...'
```

console 可以按连接器名缓存这份输出（发行包不变就不变）。是解析这段文本，还是直接调它背后的 Java 类拿结构化结果，实现时再定。

### 5.3 集群登记：引擎和地址

现在 Zeta 的地址写在环境变量（`CONSOLE_ZETA_MASTER_URLS` 等，`application.yml` 的 `console.zeta`），全局只有一个。改为在 console 里**登记集群**，任务选集群：

| 字段 | Zeta | Flink |
|---|---|---|
| 名称、说明 | ✅ | ✅ |
| 引擎类型 | `ZETA` | `FLINK` |
| 地址 | master REST 地址（可多个） | jobmanager REST 地址（如 `http://192.168.1.141:8081`） |
| 认证 | basic auth 用户名 / 密码（加密保存） | Flink REST 一般不开认证；前面有网关时再加 |
| 版本 | 读集群的 `/overview` | Flink 版本（决定用哪个客户端和启动脚本，可从 `/config` 读出来校验） |
| 提交端 | — | 做法 1：本机 Flink 客户端路径（`FLINK_HOME`）、SeaTunnel 发行包路径；做法 2：再加提交节点的连接方式和凭据 |

- 由管理员维护，放在现有的"集群"页面（现在只看一个 Zeta 集群）
- 任务（`job_definition`）记所选集群；新建任务默认选一个"默认集群"。**建好后不能换集群**：Zeta 和 Flink 的 savepoint / checkpoint 互不通用，同引擎的两个集群之间也读不到对方的存储；要换就复制任务（`JobService.copy`）到新集群，从全新启动开始
- 升级时把环境变量里的 Zeta 配置导入成第一个集群、设为默认，已有任务都指向它，行为不变
- 不同引擎的能力不同，向导和任务页按所选集群的引擎类型开关功能（例如 Zeta 专用的 `env` 设置、`/option-rules` 的来源）

表结构和页面见 §8。

### 5.4 其他要定的

- **向导**：生成的 HOCON 大体通用；`env` 里 Zeta 专用的设置（如 `checkpoint.retain-after-job-cancelled`）要按引擎换，Flink 的映射见 §2.4
- **离线调度**：XXL-JOB 执行器线程里跑命令行、等进程结束，和现在轮询 Zeta 的结构不同；`flink run` 默认附着等待（离线）还是 `-d` 分离（实时）要分开处理

### 5.5 接入一套已有的 Standalone 集群

例：console 在 `192.168.1.130`，Flink Standalone 集群在 `192.168.1.141–143`（141 为 jobmanager），集群由别人维护、checkpoint 已配好、不能改。按做法 1，**所有操作都在 130 上**：

1. **问清集群信息**（不登录集群机器）：Flink 版本（Web UI 右上角或问维护方，决定客户端版本和启动脚本）；jobmanager REST 地址（打开 Web UI 用的 `IP:端口`，或集群配置的 `rest.address` / `rest.port`；前面有负载均衡就填它的地址）
2. **装 Flink 客户端**：同版本 Flink 解压到 `/opt/flink`，不启动
3. **放 SeaTunnel 发行包**：编译产物在源码的 `seatunnel-dist/target/apache-seatunnel-<版本>-bin.tar.gz`，解压到 `/opt/seatunnel`，按 §7.1 只留用到的连接器（缺的从 `seatunnel-connectors-v2/connector-xxx/target/` 拷），`lib/` 补 JDBC 驱动，`seatunnel-env.sh` 的 `FLINK_HOME=/opt/flink`
4. **网络**：130 → 141:8081；**141–143 → 作业读写的所有数据源**（读写数据的是 taskmanager，白名单要放行这些机器；CDC 还要能连源库的复制端口、账号有复制权限）。只是开网络，不是改集群
5. **冒烟测试**：

   ```bash
   cat > /tmp/fake.conf <<'EOF'
   env { parallelism = 2, job.mode = "BATCH" }
   source { FakeSource { row.num = 20, schema = { fields { name = "string", age = "int" } } } }
   sink { Console {} }
   EOF
   /opt/seatunnel/bin/start-seatunnel-flink-20-connector-v2.sh \
     -m 192.168.1.141:8081 --config /tmp/fake.conf --name smoke-test
   ```

   成功：命令结束打印 `Job finished, execution result`，141:8081 的 Completed Jobs 里有 `smoke-test`。再跑一个 JDBC 作业验证驱动、网络和权限。报 `ClassNotFoundException` 说明 §2.5 的推断不成立，要请维护方在 141–143 同路径放一份发行包
6. **在 console 登记集群**（§5.3）：类型 Flink、地址 `http://192.168.1.141:8081`、`FLINK_HOME=/opt/flink`、发行包 `/opt/seatunnel`

| 机器 | 要有的 | 动 Flink 配置吗 |
|---|---|---|
| 130（console + 提交端） | 同版本 Flink 客户端、SeaTunnel 精简发行包（含 JDBC 驱动）、能访问 141:8081 | — |
| 141（jobmanager） | 原样 | 不改 |
| 142、143（taskmanager） | 能访问作业读写的所有数据源；§2.5 验证不通过时才需要同路径的发行包 | 不改 |

### 5.6 checkpoint / savepoint：用集群的

在 Flink 上 checkpoint 和 savepoint 是 **Flink 的**：由 Flink 触发、存储，恢复时用 `flink run -s` 读取；SeaTunnel 连接器只是把自己的状态（如 CDC 的 binlog 位点）交给 Flink 保存。Zeta 的 `seatunnel.yaml` 里 `checkpoint.storage` 在 Flink 上不起作用。

| | Zeta | Flink |
|---|---|---|
| 谁做 checkpoint | Zeta | Flink |
| 存储位置配在哪 | `config/seatunnel.yaml` 的 `checkpoint.storage` | 集群的 `config.yaml` / `flink-conf.yaml`（`state.checkpoints.dir`、`state.savepoints.dir`） |
| 作业里配什么 | `env.checkpoint.interval` | `env.checkpoint.interval` 等（§2.4） |
| 恢复 | 提交时带 `restoreMode`，Zeta 自己找 | `flink run -s <路径>`，路径由 console 记下 |

console 的做法：

- **存储目录用集群配置好的**，作业配置里不写 `execution.checkpoint.data-uri`、`flink.state.*`；作业里只设间隔等（`checkpoint.interval`）
- 停止做 savepoint：`POST /jobs/{id}/stop` 不带 `targetDirectory`，用集群默认目录；console 记下返回的 savepoint 路径，下次 `-s` 恢复
- 从 checkpoint 恢复：作业失败或取消后，最近一次保留的 checkpoint 路径从 Flink REST `/jobs/{id}/checkpoints` 读出来记下。前提是集群配置了 `execution.checkpointing.externalized-checkpoint-retention: RETAIN_ON_CANCELLATION`（否则取消后 checkpoint 被删）；没配时只能靠 savepoint，console 在集群登记时读 `/jobmanager/config` 检查并提示
- 集群没配 savepoint 目录时，停止做 savepoint 会失败；同样在登记时检查提示
- 清理见 §5.9

### 5.7 配置文件和溯源

提交时的配置有三份，作用不同：

| | 内容 | 存在哪 | 生命周期 |
|---|---|---|---|
| 任务版本 | 用户写的 HOCON 或向导表单，引用 `datasource_id`，不含密码 | console 数据库（`job_version`） | 永久，版本不可改 |
| 运行归档 | 本次实际提交的配置，变量已替换、密码打码成 `******`（design.md §6） | console 数据库 / 日志存储，和每次运行关联 | 跟随运行记录 |
| 提交用的临时文件 | 渲染好的完整配置，**带明文密码** | 提交端的临时目录 | `flink run` 结束（或 `-d` 返回）后立刻删除 |

页面上看到的配置、出问题时溯源，用的都是前两份（和现在 Zeta 一样），所以删掉临时文件**不影响溯源**。临时文件只在提交那一刻需要：`flink run` 下 main 在提交端读完配置就生成了 JobGraph，作业在集群上运行、之后从 savepoint 恢复都不再读这个文件（恢复时重新渲染一份新的）。

### 5.8 日志

Flink 上和一次运行有关的信息分在几处：

| 来源 | 内容 | 按作业吗 | 怎么取 | 留多久 |
|---|---|---|---|---|
| `flink run` 的输出 | 提交过程、SeaTunnel 读配置和加载连接器的日志、执行计划、提交失败的原因；离线作业结束时的 `FlinkJobMetricsSummary`（读写条数） | 是 | console 自己执行的命令，直接拿到 | console 存多久就多久 |
| 作业异常 | 根因异常和历次失败（含哪个 task、哪台 taskmanager） | 是 | `GET /jobs/{id}/exceptions` | 作业结束后只在 jobmanager 内存里留 `jobstore.expiration-time`（默认 1 小时），之后整个作业查不到 |
| jobmanager 日志（`jobmanager.log`、`.out`） | 所有作业的提交、调度、checkpoint 协调、failover | 否，所有作业混在一起 | `GET /jobmanager/logs` 列文件，`/jobmanager/logs/{文件名}` 取整份 | 按集群的 log4j 滚动策略，会被覆盖；jobmanager 重启后清空 |
| taskmanager 日志（`taskexecutor.log`、`.out`） | 真正读写数据的地方：SeaTunnel 连接器的日志（读了哪些表、SQL、写入、报错）；Console sink 的输出在 `.out` | 否，同一台 taskmanager 上的所有作业混在一起 | `GET /taskmanagers` 列 taskmanager，`/taskmanagers/{id}/logs`、`/taskmanagers/{id}/logs/{文件名}` | 同上；taskmanager 重启或缩容后就没了 |

做法：**不归档 jobmanager / taskmanager 日志**，页面上按需经 Flink REST 读取：

- 运行详情页的"引擎日志"改为几个入口：`flink run` 输出（console 存的）、作业异常（console 存的，见下）、jobmanager 日志和这次运行用到的 taskmanager 日志（实时读 Flink，打开时才请求）
- 读 taskmanager 日志时按作业过滤：从 `/jobs/{id}` 拿到这次运行用到的 taskmanager 和 task 名（SeaTunnel 的 task 名带 source / sink 的插件名），只展示包含 jobId 或这些 task 名的行，同时给"看整份文件"的入口。连接器自己打的日志不一定带 jobId，过滤只能做到大致
- 实时读取的限制要在页面上说明：日志可能已经滚动覆盖、taskmanager 可能已经重启；内容是集群上**所有作业**的
- console 要存的只有两样小东西：`flink run` 的输出（按次归档，和现在的 `LogStorage` 一样），作业结束时的 `/jobs/{id}/exceptions`（1 小时后就查不到了，失败原因要靠它）。作业结束时的状态、计数也要在这时取到存下

和 Zeta 的区别：Zeta 配了 `routingAppender` 后每个作业一份日志，console 在每次尝试后整份拉下来归档；Flink 没有按作业的日志文件（只有 application 模式每个作业一个集群才天然分开），所以不归档，只做实时查看。

### 5.9 savepoint / checkpoint 的清理

**Zeta 的机制**（`config/seatunnel.yaml` 的 `checkpoint.storage`，本机 `max-retained: 3`）：

- checkpoint 和 savepoint 都存在 Zeta 的 checkpoint 存储里，按作业 id 分目录；每个作业只保留最近 `max-retained` 个，更旧的由 Zeta 自动删
- 作业正常结束时删掉它的 checkpoint 数据；取消时默认也删，console 给实时任务加了 `checkpoint.retain-after-job-cancelled = true`（design.md §12）才保留
- console 从 savepoint 恢复时沿用原 jobId（`restoreSourceJobId`），新的 checkpoint 写进同一个目录，旧的 savepoint 随 `max-retained` 被挤掉。所以**正常的停止 → 恢复循环不会积累**
- 会留下的：带 savepoint 停止或失败后**再也没恢复**的作业（例如任务被删除、改成全新启动），它的目录一直在。以上是按 Zeta 的机制理解，"删除任务后目录还在"没有实测，console 现在也没有清理它

**Flink 的机制**：

- **checkpoint**：作业运行中只保留最近 `state.checkpoints.num-retained` 个（默认 1），旧的自动删。作业结束时：正常结束、取消都删掉，除非集群配了 `externalized-checkpoint-retention: RETAIN_ON_CANCELLATION`，保留下来的 Flink **不再管**；失败时保留
- **savepoint**：属于用户，Flink **从不自动删**
- **恢复模式**（1.15 起，`flink run -s <路径> -restoreMode <模式>`，经启动脚本透传）：
  - `NO_CLAIM`（默认）：Flink 不碰这份快照，新作业先做一次完整 checkpoint 和它脱钩；快照留给用户自己删
  - `CLAIM`：Flink 接管这份快照，新作业的 checkpoint 覆盖它之后由 Flink 删掉（和 Zeta 沿用 jobId 的效果相近）
  - `LEGACY`：旧行为，已废弃
- **删除接口**：`POST /savepoint-disposal`（请求体 `{"savepointPath": "..."}`），由集群去删，console 不需要访问存储；`GET /savepoint-disposal/{triggerid}` 查结果

console 的做法：

- 从 savepoint 或保留的 checkpoint 恢复时用 **`CLAIM`**：恢复成功、做完第一次 checkpoint 后旧快照由 Flink 删，正常的停止 → 恢复循环不积累，和 Zeta 一样
- 不再需要的恢复点由 console 调 `/savepoint-disposal` 删：任务删除时、选择全新启动时、换成新恢复点而旧的没被 CLAIM 掉时（例如带 savepoint 停止后又选从 checkpoint 恢复）
- 删除失败（集群不可达、路径已不存在）只记一条消息，不阻塞任务删除
- 保留的 checkpoint 也是同一个接口删（它和 savepoint 一样是一个目录），能否用 `/savepoint-disposal` 删 checkpoint 待实测；不行就只能交给集群维护方
- Zeta 侧"删除任务后目录还在"另记一条待办，不在本方案里

## 6. 实现顺序建议

1. 先做 §7.6 的验证
2. 集群登记和任务选集群（§5.3），Zeta 行为不变
3. Flink 离线：提交（做法 1）、状态、停止、日志、计数，跑通 XXL-JOB 调度
4. Flink 实时：savepoint 停止和恢复、checkpoint 恢复、自动重启，按 design.md §12 的实测表在 Flink 上重测 DDL 和 transform
5. 每一步都用 §7 的本地环境实测

## 7. 本地测试环境（Docker 单节点）

可行。SeaTunnel 自己的 e2e 就是这么做的（`seatunnel-e2e-common` 的 `AbstractTestFlinkContainer`）：起单节点容器，在容器里执行启动脚本。按 §0 的前提，本地这套 Flink 就是"一个可访问的 Flink 集群"。

### 7.1 准备精简的发行包

不动 Zeta 正在用的发行包，另复制一份只带需要的连接器：

```bash
SRC=~/IdeaProjects/data-integration/apache-seatunnel-3.0.1-SNAPSHOT   # 本机 Zeta 用的发行包
DST=~/software/seatunnel-engines
rsync -a --exclude connectors --exclude logs "$SRC/" "$DST/"
mkdir -p "$DST/connectors" "$DST/logs"
cp "$SRC/connectors/plugin-mapping.properties" "$DST/connectors/"
for c in jdbc cdc-base cdc-mysql cdc-postgres elasticsearch http-base fake console; do
  cp "$SRC/connectors/connector-$c-3.0.1-SNAPSHOT.jar" "$DST/connectors/"
done
```

### 7.2 端口

只映射看界面用的端口；容器之间的通信端口在 Docker 网络里，不和宿主机冲突。

| 端口 | 用途 | 本机现状 | 映射 |
|---|---|---|---|
| Flink 8081 | Web UI + REST | 被 xxl-job-admin 占用 | **8082:8081** |
| Flink 6123 / 6124 | RPC / blob | 空闲 | 不映射 |

### 7.3 `docker-compose.yml`

```yaml
# Flink 1.20 单节点（jobmanager + taskmanager）。
name: seatunnel-engines

x-common: &common
  extra_hosts:
    - "host.docker.internal:host-gateway"   # 容器里访问宿主机上的数据库（Linux 必须加）
  volumes:
    - ${SEATUNNEL_DIST:-~/software/seatunnel-engines}:/opt/seatunnel   # 验证 §7.6 第 1 项时去掉这行
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
```

这里的 checkpoint 配置是在模拟"集群维护方已经配好"（§5.6）。

### 7.4 提交

两种方式：在容器里提交（和 SeaTunnel e2e 一样，用来确认发行包本身没问题），和从宿主机提交（模拟做法 1，宿主机要有同版本 Flink 客户端）：

```bash
docker compose up -d                        # http://localhost:8082

# 容器里
docker compose exec jobmanager \
  /opt/seatunnel/bin/start-seatunnel-flink-20-connector-v2.sh --config /opt/jobs/demo.conf

# 宿主机（做法 1）
FLINK_HOME=~/software/flink-1.20.1 ~/software/seatunnel-engines/bin/start-seatunnel-flink-20-connector-v2.sh \
  -m localhost:8082 --config jobs/demo.conf --name demo
```

### 7.5 注意

- 作业配置里的数据库地址写 `host.docker.internal`（或数据库的真实 IP），不能写 `127.0.0.1`；从 console 复制的配置要手动填密码（console 只在提交时注入）
- 镜像要先拉好：本机 Docker Hub 走代理会失败。`flink:1.20.1-scala_2.12-java11` 有 arm64；SeaTunnel e2e 用的 `tyrantlucifer/*` 只有单一架构
- 官方 Flink 镜像不带 Hadoop。JDBC、CDC、ES、HTTP 用不到；HDFS、S3 等文件类连接器可能缺类，到时按需补
- 已有的一份 Flink compose（1.19.1、端口 8081、挂载 `/mnt/flink`）的问题：1.19 不在支持范围，改 1.20.1；没配 checkpoint / savepoint 目录，CDC 停止做 savepoint 会失败；在这台 Mac 上 8081 冲突、`/mnt` 不能用；Linux 上访问宿主机数据库要加 `extra_hosts`

### 7.6 要先做的验证

1. **集群节点不放发行包能否运行**（§2.5）：jobmanager / taskmanager 两个容器**都不挂** SeaTunnel 发行包，从宿主机以 `-m localhost:8082` 提交 FakeSource → Console，再提交一个 JDBC 作业。通过则集群上什么都不用装
2. **savepoint 停止和恢复**：实时作业 `-d` 提交，`POST /jobs/{id}/stop` 拿到 savepoint 路径，再 `-s <路径> -restoreMode claim` 恢复（确认启动脚本能透传、做完 checkpoint 后旧 savepoint 被删）；`/savepoint-disposal` 能否删保留的 checkpoint
3. **按作业名找回 jobId、固定 jobId**：`/jobs/overview` 能否按 `--name` 找到；`$internal.pipeline.job-id` 能否让 console 指定 Flink jobId（§5.2 "不重复提交"）
4. **计数**：SeaTunnel 的读写条数在 Flink REST 的哪个指标里，运行中能否拿到
5. **离线读选项**：`seatunnel-connector.sh -o` 对向导用到的连接器和 transform 是否都能输出（§5.2）
6. **日志**：taskmanager 日志里 SeaTunnel 连接器的行能否按 jobId / task 名过滤出来；作业结束后 `/jobs/{id}/exceptions` 的保留时间（§5.8）

## 8. 待讨论

1. 集群登记（§5.3）的表结构和页面
2. Zeta 侧：删除任务后它在 Zeta checkpoint 存储里的目录是否还在、要不要清理（§5.9，不属于本方案）

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

seatunnel-console generates, schedules and monitors Apache SeaTunnel 3.0 batch jobs. Design and decisions: `docs/design.md` (read the relevant section before changing behaviour; it records why things are the way they are). Table docs: `docs/schema.md`. User-facing setup: `README.md`. UI text and docs are in Chinese; code, comments and commit messages in English.

## Commands

Backend (JDK 17, Maven multi-module; use `-o` offline when the local repo is warm):

```bash
mvn -q install                                   # build all modules + run tests
mvn -q install -DskipTests                       # build only; jar: console-server/target/seatunnel-console.jar
mvn -q test -pl console-server -Dtest=WizardGeneratorTest          # one test class
mvn -q test -pl console-server -Dtest='WizardGeneratorTest#hourlyWindowAndPerTableIncrementalField'
```

Run locally (config comes from `dev.env`, gitignored, copied from `dev.env.example`; a local copy may contain non-`KEY=VALUE` lines, so load only the assignments). Setting up MySQL, xxl-job-admin and the SeaTunnel cluster is described in README's quick start:

```bash
set -a; eval "$(grep -E '^[A-Z_][A-Z0-9_]*=' dev.env)"; set +a
java -jar console-server/target/seatunnel-console.jar      # http://localhost:8088
cd console-ui && npm run dev                               # http://localhost:5173, proxies /api to 8088
```

Frontend has no test or lint script; `npm run build` (`vue-tsc -b && vite build`) is the type check. Full stack in Docker: `docker/docker-compose.yml` with `docker/.env` (template `docker/.env.example`); the SeaTunnel cluster is never part of it.

When the shell has an HTTP proxy set, local `curl` needs `--noproxy '*'`.

## Architecture

Modules: `console-common` (Result, ConsoleException, ErrorCodes, UuidV7) ← `console-engine` (EngineClient + Zeta REST impl) and `console-datasource` (one plugin per datasource type: MySQL, PostgreSQL, Elasticsearch, HTTP) ← `console-server` (Spring Boot 4.1, MyBatis-Plus, Flyway, Spring Security, embedded XXL-JOB executor) ; `console-ui` (Vue 3 + Element Plus, themed to look like Ant Design via CSS tokens in `src/styles/main.css`).

Hard constraints:
- The console talks to SeaTunnel **only via the Zeta REST API v2** (`EngineClient`); never depend on SeaTunnel jars. `EngineClient` exists so Flink/Spark engines can be added later.
- Datasource passwords never appear in stored config. Job templates hold `datasource_id = "<id>"`; `HoconTemplate.render` injects connector options (decrypted with `CONSOLE_SECRET_KEY`, AES-GCM) in memory at submit time; the archived config is masked (`******`). Field-encryption keys work the same way: configs hold `encrypt_key_id`, swapped for the key from `encrypt_key` (`encryptkey/EncryptKeyService`) at submit; a plain `key` in a FieldEncrypt block is refused like a password.
- The console is single-instance: run cancel signals, login lockouts and account-change markers live in memory.

How a run flows (`run/` and `scheduler/` packages):
1. Every batch job owns exactly one XXL-JOB job (`job_definition.xxl_job_id`), created lazily as trigger-only and switched to CRON when a schedule is enabled. The console is the source of truth; `XxlJobReconciler` re-pushes state every 10 min (XXL-JOB's OpenAPI has no read endpoint).
2. Manual run / rerun / backfill: `RunService.start` pre-creates a PENDING `job_instance`, then calls XXL-JOB `triggerJob` with the instance id in the executor param. Cron triggers create the instance in the handler.
3. `SeatunnelJobHandler` → `RunExecutor.execute`: claims the instance (conditional PENDING→RUNNING), renders variables then datasources (`JobConfigRenderer`), submits and polls via `BatchJobRunner`. Retries happen **inside one instance** as `job_instance_attempt` rows; XXL-JOB's own retry is always 0. After each attempt the SeaTunnel job log is pulled (`EngineLogService`) and archived (`LogStorage`); after success the optional row-count validation runs (`RowCountValidator`).
4. Backfills are paced by `BackfillService.drive` (one run at a time per job), because XXL-JOB runs one job's triggers on a single thread.

Job pages: `/jobs/:id` is read-only (overview with monitoring, runs, backfills, versions; `run/JobOverviewService`); `/jobs/:id/edit` holds config, schedule and basic info, each saved on its own. Engine metrics are sampled on every poll (`RunRecorder.progress` → `job_metric_sample`, parsed by `run/EngineMetrics`), see design.md §10.4.

Job editing (`job/` package): script mode stores HOCON as written; wizard mode stores a `WizardForm` (`job_version.form_json`) and the server generates the HOCON with `wizard/WizardGenerator` (pure, unit-tested) and checks it with `wizard/WizardService.precheck` against live datasources. Both only orchestrate: the source and sink parts come from a `WizardSource` (`wizard/source`: Jdbc, Http) and a `WizardSink` (`wizard/sink`: Jdbc, Elasticsearch) that `WizardConnectors` picks from the connector each datasource plugin declares in its `DataSourceCapabilities` (design.md §6.1, §7.14). A source keeps its own settings in `form.source.spec` (forms saved with `source.http` still load). `wizard-golden/*.json` lock the generated config; `WizardGoldenTest` compares them. Wizard → script is one-way. Versions are immutable; rollback saves an old version as a new one; copying a job (`JobService.copy`) makes its current version v1 of a new job.

Wizard transforms (design.md §7.8–7.12): table/field names, field processing (replace, mask, convert, encrypt), added and extracted fields all become SeaTunnel transforms between source and sink, in a fixed order per source table: JsonPath → RegexExtract → Split → one `Sql` query (`wizard/FieldProcessing`) → FieldEncrypt → FieldRename → TableRename. Target names are computed by the console in two places that must agree: `WizardGenerator.targetName/targetField` and `console-ui/src/utils/naming.ts` (likewise `utils/timeExpr.ts` mirrors `TimeExpression` for previews). Chinese labels for connector options live in `console-ui/src/utils/connectorOptions.ts`.

Variables (`job/Variables`, `TimeExpression`): DolphinScheduler syntax (`${name}`, `${system.biz.date}`, `$[yyyyMMdd-1]`), resolved textually before HOCON parsing against the instance's scheduled time. Unknown `${...}` is left for HOCON; SeaTunnel sink placeholders (`${table_name}`, `${primary_key}`, …) are reserved and never substituted.

Ownership (`auth/Access`): everyone can read everything; only the creator or an admin may change, delete or run projects, datasources and jobs (runs and schedules follow their job). A job may only use datasources owned by its creator. Guards sit in user-facing service methods, not in code called from scheduler threads.

## Adding a datasource type

Nothing outside this list should need to change (design.md §7.14); main code never compares type names.

1. `console-datasource`: a value in `DataSourceType` and a plugin registered in `DataSourcePlugins`. It implements validation, connection test, metadata, `toConnectorOptions`, `address` and `capabilities` (wizard connectors, namespaces, multi-table, `countBy`, max parallelism, lower-case names, JDBC driver). A JDBC database extends `JdbcDataSourcePlugin` (`buildUrl`, `defaultPort`, `dialect`, primary-key SQL) and reuses `SqlDialect.MYSQL` / `POSTGRESQL` or adds one. `datasource.type` is a plain VARCHAR, no migration.
2. `console-ui/src/components/datasource/forms.ts`: its connection form; a JDBC database reuses `JdbcFields` with its default port.
3. Only when its SeaTunnel connector is new to the wizard: a `WizardSource` or `WizardSink` in `job/wizard/source|sink` plus a case in `WizardConnectors`; on the UI an entry in `components/wizard/connectors.ts` (a source also brings its step-one component); Chinese labels for the connector's options in `utils/connectorOptions.ts`.
4. Tests: the plugin in `DataSourcePluginsTest`; for the wizard a scenario in `console-server/src/test/resources/wizard-golden/` (write the `.golden` with `-Dgolden.update=true` and review it), then run a generated config on the cluster.

## Conventions and gotchas

- **Flyway**: add `V<n>__*.sql`; never edit an applied migration.
- IDs are UUID v7 strings (`CHAR(36)`). Soft delete uses `deleted_at` + `@TableLogic`; custom `@Select/@Update` in mappers bypass it on purpose where noted.
- Times are `LocalDateTime` in the JVM zone; pooled MySQL connections set `time_zone` to match (`PersistenceConfig`).
- Jackson is v3 (`tools.jackson.*`); records without defaults reject missing primitive fields, so use wrapper types for optional request fields.
- Lookup helpers must not return `Map.of()` when callers may `get(null)` (it throws); use `HashMap`.
- Never resubmit a job the engine may already have: submissions carry a console-chosen `jobId` (Zeta skips a second submit of a running id); `ENGINE_UNAVAILABLE` means certainly not delivered, `ENGINE_RESPONSE_LOST` means maybe delivered. `BatchJobRunner` throws only when the engine certainly did not get the job; everything else ends in an `Outcome` (UNKNOWABLE = may still be running: never retried, backfills pause).
- Updates write only the columns they change (`update(partialEntity, wrapper)`), never a whole entity read earlier: a stale `created_by`, `xxl_job_id` or name would overwrite concurrent changes.
- Name searches go through `Pages.literal` (escapes `%` and `_`). Detail pages set the header breadcrumb with `setBreadcrumb` and put a `BackButton` before the title.
- Verified SeaTunnel 3.0 behaviour worth knowing: `/submit-job` returns the job id as a string; a job rejected at submission gives HTTP 500 without the cause (`ScriptChecker` reproduces the source queries to find it); per-job logs need `routingAppender` in every node's `log4j2.properties`; `/option-rules?type=source|sink|transform&plugin=` returns option definitions (wizard advanced options, script transform templates).
- UI state that should survive a refresh lives in the URL: list filters and page via `composables/useQueryState`, the job tab or edit section as a path segment (`/jobs/:id/backfills`, `/jobs/:id/edit/schedule`), the wizard step and run attempt as `?step=` / `?attempt=`. `MainLayout` keys the page by route record + `:id`, so query and tab changes do not remount it.
- Generated transforms are configured per table through `table_transform` (keyed by the source `table_path`); the top level repeats the first entry only to satisfy required options and sets `table_match_regex = "(?!)"` so other tables pass through. In the `Sql` transform write columns bare (`from dual`): a backquoted column loses its declared length (a CHAR primary key becomes TEXT and MySQL auto-create fails); double quotes are not accepted. JsonPath needs `column_error_handle_way = "SKIP"` or a missing path fails the job. To check generated config against the engine, submit it to Zeta directly (`POST /submit-job?format=hocon`, basic auth) with the test databases' connection written in, and inspect the target tables.
- Schedules fire at most every 2 minutes (`CronSchedule.parseSchedule`); backfills only run times that have come and one open batch per job.
- `@WebMvcTest` slices (e.g. `ClusterControllerTest`) need `@MockitoBean` for every bean the security filter chain and controller pull in.
- Commits end with the `Co-Authored-By` line given by the environment.

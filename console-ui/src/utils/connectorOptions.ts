/*
 * Plain-language names and help for connector options shown under the wizard's advanced options
 * (docs/design.md §7.1). The cluster supplies which options exist, their types and defaults; the
 * wording here is ours. Options marked hidden are handled by the wizard, belong to the datasource,
 * or do not apply to the wizard's databases; one still shows when a value was set for it earlier.
 */

export type OptionControl = 'bool' | 'number' | 'text' | 'select'

export interface OptionText {
  label: string
  help: string
  group: string
  control?: OptionControl
  choices?: { value: string; label: string }[]
  /** shown only for this target database type */
  onlyFor?: string
  /** rarely needed: listed under "更多参数", folded by default */
  rare?: boolean
}

export interface OptionDict {
  /** group titles in display order */
  groups: string[]
  options: Record<string, OptionText>
  hidden: string[]
}

const SOURCE_JDBC: OptionDict = {
  groups: ['读取性能', '并行切分', '类型转换', '连接'],
  options: {
    fetch_size: {
      label: '每次读取行数',
      help: '每次从数据库取回多少行。0 表示由驱动决定；大表可以设 1000～10000，减少往返次数，设太大会占用更多内存。',
      group: '读取性能',
      control: 'number',
    },
    use_select_count: {
      label: '用 count(*) 统计行数',
      help: '切分前需要知道表有多少行。默认读数据库的统计信息；统计信息不准导致分片很不均匀时再打开，大表执行 count(*) 会比较慢。',
      group: '读取性能',
      control: 'bool',
    },
    skip_analyze: {
      label: '跳过行数统计',
      help: '不统计表的行数。只在统计本身很慢、又不在意分片是否均匀时打开。',
      group: '读取性能',
      control: 'bool',
      rare: true,
    },
    enable_concurrent_read: {
      label: '单表并行读取',
      help: '并行度大于 1 时，把一张表切成多个分片同时读。关闭后每张表只用一个线程读。',
      group: '并行切分',
      control: 'bool',
    },
    'split.size': {
      label: '每个分片的行数',
      help: '并行读取时，每个分片大约包含多少行。表很大时调大可以减少分片数量。',
      group: '并行切分',
      control: 'number',
    },
    partition_column: {
      label: '切分字段',
      help: '按哪个字段把表切成分片，默认用主键。没有主键的表可以指定一个数字或日期字段，最好是有索引、分布均匀的字段。',
      group: '并行切分',
    },
    partition_num: {
      label: '分片数',
      help: '把表切成多少个分片。不填时按"每个分片的行数"自动计算。',
      group: '并行切分',
      control: 'number',
    },
    partition_lower_bound: {
      label: '切分范围最小值',
      help: '切分字段的起始值。不填时自动查询表中的最小值。',
      group: '并行切分',
      rare: true,
    },
    partition_upper_bound: {
      label: '切分范围最大值',
      help: '切分字段的结束值。不填时自动查询表中的最大值。',
      group: '并行切分',
      rare: true,
    },
    'split.string_split_mode': {
      label: '字符串主键的切分方式',
      help: '主键是字符串时怎么切分：抽样（默认，按抽样结果决定分片边界）或按字符集计算。抽样一般就够用。',
      group: '并行切分',
      control: 'select',
      choices: [
        { value: 'sample', label: '抽样' },
        { value: 'charset_based', label: '按字符集计算' },
      ],
      rare: true,
    },
    'split.string-strategy': {
      label: '字符串字段的切分策略',
      help: '切分字段是字符串时：自动、按范围、按哈希，或者不切分（整表一个分片）。',
      group: '并行切分',
      control: 'select',
      choices: [
        { value: 'auto', label: '自动' },
        { value: 'range', label: '按范围' },
        { value: 'hash', label: '按哈希' },
        { value: 'none', label: '不切分' },
      ],
      rare: true,
    },
    'split.string_split_mode_collate': {
      label: '按字符集切分时的排序规则',
      help: '只在"按字符集计算"且表使用了特殊排序规则（collation）时填写，例如 utf8mb4_bin。',
      group: '并行切分',
      rare: true,
    },
    'split.allow-sampling': {
      label: '允许抽样切分',
      help: '数据分布不均匀时，是否通过抽样来决定分片边界。关闭后会按不均匀的方式切分。',
      group: '并行切分',
      control: 'bool',
      rare: true,
    },
    'split.even-distribution.factor.upper-bound': {
      label: '均匀分布判断上限',
      help: '判断切分字段是否分布均匀的阈值上限（最大值 - 最小值 + 1）/ 行数。一般不需要改。',
      group: '并行切分',
      control: 'number',
      rare: true,
    },
    'split.even-distribution.factor.lower-bound': {
      label: '均匀分布判断下限',
      help: '判断切分字段是否分布均匀的阈值下限。一般不需要改。',
      group: '并行切分',
      control: 'number',
      rare: true,
    },
    'split.sample-sharding.threshold': {
      label: '启用抽样切分的分片数阈值',
      help: '估算的分片数超过这个值、且分布不均匀时，改用抽样切分。一般不需要改。',
      group: '并行切分',
      control: 'number',
      rare: true,
    },
    'split.inverse-sampling.rate': {
      label: '抽样比例的倒数',
      help: '抽样切分时每多少行取一行，例如 1000 表示抽取 1/1000。表特别大时可以调大。',
      group: '并行切分',
      control: 'number',
      rare: true,
    },
    decimal_type_narrowing: {
      label: '无小数位的 decimal 转为整数',
      help: '没有小数位的 decimal（如 decimal(10,0)）在不丢精度时转成 int 或 bigint。关闭则保持 decimal 类型。',
      group: '类型转换',
      control: 'bool',
    },
    int_type_narrowing: {
      label: 'tinyint(1) 转为布尔',
      help: 'MySQL 的 tinyint(1) 当作布尔值读取。如果这个字段实际存的是 0/1 以外的数字，请关闭。',
      group: '类型转换',
      control: 'bool',
    },
    connection_check_timeout_sec: {
      label: '连接检查超时（秒）',
      help: '检查数据库连接是否可用时最多等待多少秒。',
      group: '连接',
      control: 'number',
      rare: true,
    },
    properties: {
      label: '额外的连接参数',
      help: '附加的 JDBC 连接参数，格式 {key = "value"}。密码等敏感信息不要写在这里，请放在数据源里。',
      group: '连接',
      rare: true,
    },
  },
  hidden: ['use_regex', 'parallelism', 'dialect', 'compatible_mode', 'username', 'password', 'url', 'driver', 'table_list',
    'table_path', 'query', 'where_condition'],
}

const SINK_JDBC: OptionDict = {
  groups: ['写入性能', '写入行为', '自动建表', '连接'],
  options: {
    batch_size: {
      label: '每批写入行数',
      help: '攒够多少行提交一次。调大能提高写入速度，但每批占用的内存和单次事务也更大。',
      group: '写入性能',
      control: 'number',
    },
    batch_interval_ms: {
      label: '定时提交间隔（毫秒）',
      help: '没攒够一批时，最多隔多久也提交一次。0 表示不按时间提交，只按行数。',
      group: '写入性能',
      control: 'number',
    },
    multi_table_sink_replica: {
      label: '多表写入线程数',
      help: '同步多张表时，同时写入的线程数。目标库压力允许时可以调大。',
      group: '写入性能',
      control: 'number',
    },
    use_copy_statement: {
      label: '用 COPY 批量写入',
      help: 'PostgreSQL 目标用 COPY 命令写入，速度更快，但不能按主键 upsert，只适合追加写入。',
      group: '写入性能',
      control: 'bool',
      onlyFor: 'POSTGRESQL',
    },
    max_retries: {
      label: '写入失败重试次数',
      help: '一批数据写入失败后重试几次，0 表示不重试。整个任务的失败重试在"调度"页配置。',
      group: '写入行为',
      control: 'number',
    },
    is_primary_key_updated: {
      label: '更新时同时写主键列',
      help: 'upsert 更新已有行时是否把主键列也写一遍。目标库不允许更新主键（如某些分布式数据库）时关闭。',
      group: '写入行为',
      control: 'bool',
    },
    create_index: {
      label: '自动建表时创建索引',
      help: '目标表不存在、由任务自动创建时，是否同时创建源表上的索引。关闭可以加快首次写入，之后再手动建索引。',
      group: '自动建表',
      control: 'bool',
    },
    connection_check_timeout_sec: {
      label: '连接检查超时（秒）',
      help: '检查数据库连接是否可用时最多等待多少秒。',
      group: '连接',
      control: 'number',
      rare: true,
    },
  },
  hidden: ['oracle_insert_mode', 'is_exactly_once', 'xa_data_source_class_name', 'max_commit_attempts', 'transaction_timeout_sec',
    'auto_commit', 'support_upsert_by_insert_only', 'field_ide', 'tablePrefix', 'tableSuffix', 'table_options', 'dialect',
    'compatible_mode', 'custom_sql', 'query', 'username', 'password', 'url', 'driver', 'database', 'table', 'generate_sink_sql',
    'enable_upsert', 'schema_save_mode', 'data_save_mode', 'primary_keys'],
}

const SINK_ES: OptionDict = {
  groups: ['写入性能', '写入行为', '连接'],
  options: {
    max_batch_size: {
      label: '每批写入文档数',
      help: '一次 bulk 请求写入多少条文档。默认 10 偏小，数据量大时可以设到 500～5000。',
      group: '写入性能',
      control: 'number',
    },
    multi_table_sink_replica: {
      label: '多表写入线程数',
      help: '同步多张表时，同时写入的线程数。',
      group: '写入性能',
      control: 'number',
    },
    max_retry_count: {
      label: '写入失败重试次数',
      help: '一次 bulk 请求失败后最多尝试几次。',
      group: '写入行为',
      control: 'number',
    },
    key_delimiter: {
      label: '联合主键分隔符',
      help: '表有多个主键字段时，用这个符号把它们拼成文档 _id，默认 "_"，例如 1_2。',
      group: '写入行为',
    },
    tls_verify_certificate: {
      label: '校验 HTTPS 证书',
      help: '连接 HTTPS 地址时是否校验证书。只在测试环境使用自签名证书时关闭。',
      group: '连接',
      control: 'bool',
    },
    tls_verify_hostname: {
      label: '校验 HTTPS 主机名',
      help: '连接 HTTPS 地址时是否校验证书里的主机名。',
      group: '连接',
      control: 'bool',
    },
  },
  hidden: ['index_type', 'auth_type', 'username', 'password', 'hosts', 'index', 'primary_keys', 'schema_save_mode', 'data_save_mode',
    'tls_keystore_path', 'tls_keystore_password', 'tls_truststore_path', 'tls_truststore_password', 'vectorization_fields',
    'vector_dimensions'],
}

const SOURCE_HTTP: OptionDict = {
  groups: ['请求重试', 'JSON 解析'],
  options: {
    retry: {
      label: '失败重试次数',
      help: '请求发生网络异常时的最大重试次数。HTTP 状态码错误不会按这个参数重试。',
      group: '请求重试',
      control: 'number',
    },
    retry_backoff_multiplier_ms: {
      label: '重试退避基数（毫秒）',
      help: '每次重试前等待时间的增长基数，默认 100 毫秒。',
      group: '请求重试',
      control: 'number',
      rare: true,
    },
    retry_backoff_max_ms: {
      label: '最大退避时间（毫秒）',
      help: '每次重试前最多等待多久，默认 10000 毫秒。',
      group: '请求重试',
      control: 'number',
      rare: true,
    },
    json_filed_missed_return_null: {
      label: '缺少 JSON 字段时返回空值',
      help: '记录缺少已配置字段时写入 null，而不是让解析失败。参数名中的 filed 是 SeaTunnel 的既有拼写。',
      group: 'JSON 解析',
      control: 'bool',
    },
  },
  hidden: ['url', 'method', 'params', 'body', 'headers', 'schema', 'content_field', 'json_field', 'format', 'pageing',
    'keep_params_as_form', 'keep_page_param_as_http_param', 'connect_timeout_ms', 'socket_timeout_ms', 'poll_interval_millis',
    'binary_chunk_size', 'enable_multi_lines'],
}

/** MySQL-CDC and Postgres-CDC (design.md §12 P2): the snapshot, the change stream, the connection. */
const SOURCE_CDC: OptionDict = {
  groups: ['全量阶段', '增量阶段', '连接'],
  options: {
    'snapshot.split.size': {
      label: '全量分片行数',
      help: '全量阶段按主键把表切成分片，每片大约多少行。表很大时调大可以减少分片数量。',
      group: '全量阶段',
      control: 'number',
    },
    'snapshot.fetch.size': {
      label: '全量每次读取行数',
      help: '全量阶段每次从数据库取回多少行。',
      group: '全量阶段',
      control: 'number',
    },
    'incremental.parallelism': {
      label: '增量阶段并行度',
      help: '读取 binlog / WAL 的并行度，一般保持 1。',
      group: '增量阶段',
      control: 'number',
      rare: true,
    },
    exactly_once: {
      label: '精确一次',
      help: '默认关闭：至少一次，靠按主键写入保证重复写入结果一样。打开后开销更大，一般不需要。',
      group: '增量阶段',
      control: 'bool',
      rare: true,
    },
    'server-id': {
      label: 'server-id',
      help: 'MySQL 复制连接用的 server-id（一个或一段，如 5400-5403），console 已为每个任务自动分配不冲突的一段。只在和其他同步工具（如 Canal）撞号时修改。',
      group: '增量阶段',
      rare: true,
    },
    'server-time-zone': {
      label: '数据库时区',
      help: '数据库的时区，例如 Asia/Shanghai。时间字段同步后差了几个小时时设置。',
      group: '连接',
    },
    'connect.timeout.ms': {
      label: '连接超时（毫秒）',
      help: '连接数据库的超时时间。',
      group: '连接',
      control: 'number',
      rare: true,
    },
    'connect.max-retries': {
      label: '连接重试次数',
      help: '连接数据库失败时重试几次。',
      group: '连接',
      control: 'number',
      rare: true,
    },
    'connection.pool.size': {
      label: '连接池大小',
      help: '全量阶段读取用的连接数。',
      group: '连接',
      control: 'number',
      rare: true,
    },
  },
  hidden: ['url', 'username', 'password', 'database-names', 'schema-name', 'table-names', 'table-pattern', 'database-pattern',
    'startup.mode', 'startup.timestamp', 'startup.specific-offset.file', 'startup.specific-offset.pos',
    'startup.specific-offset.gtid-set', 'startup.specific-offset.skip-events', 'startup.specific-offset.skip-rows',
    'stop.mode', 'stop.timestamp', 'stop.specific-offset.file', 'stop.specific-offset.pos', 'schema-changes.enabled',
    'schema-changes.include', 'schema-changes.exclude', 'decoding.plugin.name', 'slot.name', 'debezium', 'format',
    'table-names-config', 'hostname', 'port'],
}

const DICTS: Record<string, OptionDict> = {
  'source:MySQL-CDC': SOURCE_CDC,
  'source:Postgres-CDC': SOURCE_CDC,
  'source:Jdbc': SOURCE_JDBC,
  'source:Http': SOURCE_HTTP,
  'sink:Jdbc': SINK_JDBC,
  'sink:Elasticsearch': SINK_ES,
}

export function optionDict(type: 'source' | 'sink', plugin: string): OptionDict {
  return DICTS[`${type}:${plugin}`] ?? { groups: [], options: {}, hidden: [] }
}

/** The control for an option the dictionary does not describe, from the cluster's Java type. */
export function controlOf(type: string | null): OptionControl {
  if (!type) return 'text'
  if (/Boolean/.test(type)) return 'bool'
  if (/Integer|Long|Double|Float/.test(type)) return 'number'
  return 'text'
}

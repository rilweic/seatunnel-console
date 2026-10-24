<script setup lang="ts">
/**
 * Inserts a SeaTunnel transform into a script (docs/design.md §7.12): a commented template per
 * plugin, plus the plugin's options as the cluster describes them. The wizard covers the common
 * cases without writing config; this is for people who write scripts.
 */
import { computed, ref, watch } from 'vue'

import { connectorApi, type ConnectorOption } from '@/api/console'

const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ insert: [snippet: string] }>()

interface Template {
  plugin: string
  label: string
  help: string
  text: string
}

const TEMPLATES: Template[] = [
  {
    plugin: 'Sql',
    label: 'SQL 处理',
    help: '用 SeaTunnel SQL 改值、加字段、过滤行；函数见 SeaTunnel 文档「SQL Functions」',
    text: `  Sql {
    plugin_input = ["source"]      # 上一步的 plugin_output
    plugin_output = "sql_out"
    # from 后面写 dual；字段直接写名字（加反引号会丢掉字段长度）
    query = "select id, UPPER(name) as name, NOW() as etl_time from dual where status = 1"
  }`,
  },
  {
    plugin: 'FieldRename',
    label: '字段改名',
    help: '按规则或逐个改字段名，主键约束跟着改名',
    text: `  FieldRename {
    plugin_input = ["source"]
    plugin_output = "renamed"
    convert_case = "LOWER"         # 可选：UPPER / LOWER
    prefix = ""                    # 可选：加前缀
    specific = [
      { field_name = "old_name", target_name = "new_name" }
    ]
  }`,
  },
  {
    plugin: 'TableRename',
    label: '表改名',
    help: '改目标表名：大小写、前后缀、正则替换',
    text: `  TableRename {
    plugin_input = ["source"]
    plugin_output = "tables_renamed"
    prefix = "ods_"                # 可选
    suffix = ""                    # 可选
    convert_case = "LOWER"         # 可选：UPPER / LOWER
  }`,
  },
  {
    plugin: 'Filter',
    label: '选择字段',
    help: '只保留（或去掉）列出的字段',
    text: `  Filter {
    plugin_input = ["source"]
    plugin_output = "filtered"
    include_fields = ["id", "name"]   # 或用 exclude_fields 列出要去掉的
  }`,
  },
  {
    plugin: 'Replace',
    label: '值替换',
    help: '把某个字段里的文字换掉，支持正则',
    text: `  Replace {
    plugin_input = ["source"]
    plugin_output = "replaced"
    replace_fields = ["name"]
    pattern = " "
    replacement = "_"
    is_regex = false
  }`,
  },
  {
    plugin: 'Copy',
    label: '复制字段',
    help: '把一个字段复制成新字段',
    text: `  Copy {
    plugin_input = ["source"]
    plugin_output = "copied"
    fields { name_copy = name }       # 新字段 = 源字段
  }`,
  },
  {
    plugin: 'JsonPath',
    label: 'JSON 提取',
    help: '按 JSON 路径从字段里取值，成为新字段',
    text: `  JsonPath {
    plugin_input = ["source"]
    plugin_output = "json_out"
    columns = [
      # 取不到时置空，不让任务失败
      { src_field = "profile", path = "$.address.city", dest_field = "city", column_error_handle_way = "SKIP" }
    ]
  }`,
  },
  {
    plugin: 'RegexExtract',
    label: '正则提取',
    help: '按正则分组取出多个新字段',
    text: `  RegexExtract {
    plugin_input = ["source"]
    plugin_output = "regex_out"
    source_field = "email"
    regex_pattern = "^([^@]+)@(.+)$"  # 每对括号一个新字段
    output_fields = ["user", "domain"]
  }`,
  },
  {
    plugin: 'Split',
    label: '拆分字段',
    help: '按分隔符拆成多个新字段',
    text: `  Split {
    plugin_input = ["source"]
    plugin_output = "split_out"
    split_field = "tags"
    separator = ","
    output_fields = ["tag1", "tag2"]
  }`,
  },
  {
    plugin: 'FieldEncrypt',
    label: '字段加密',
    help: '用「加密密钥」里的密钥加密字段；不要直接写 key',
    text: `  FieldEncrypt {
    plugin_input = ["source"]
    plugin_output = "encrypted"
    fields = ["phone"]
    # 填「运维管理 → 加密密钥」里的密钥 ID，运行时注入，配置里不能直接写 key
    encrypt_key_id = ""
  }`,
  },
  {
    plugin: 'TableFilter',
    label: '过滤表',
    help: '多表同步时只让部分表往下走',
    text: `  TableFilter {
    plugin_input = ["source"]
    plugin_output = "tables_filtered"
    table_pattern = "^ods_.*"       # 按表名正则保留
  }`,
  },
]

const picked = ref<Template>(TEMPLATES[0])
const text = ref(TEMPLATES[0].text)
const options = ref<ConnectorOption[]>([])
const optionsError = ref('')
const loading = ref(false)

watch(visible, (open) => {
  if (open) pick(picked.value)
})

async function pick(t: Template) {
  picked.value = t
  text.value = t.text
  options.value = []
  optionsError.value = ''
  loading.value = true
  try {
    options.value = (await connectorApi.options('transform', t.plugin)).filter((o) => !['table_transform', 'table_match_regex', 'rule_match_mode'].includes(o.key))
  } catch {
    optionsError.value = '无法从 SeaTunnel 集群读取这个插件的参数说明'
  } finally {
    loading.value = false
  }
}

const common = computed(() => '多表时每个 transform 都可以用 table_transform 按表单独配置、用 table_match_regex 限定作用的表')

function insert() {
  emit('insert', text.value)
  visible.value = false
}
</script>

<template>
  <el-dialog v-model="visible" title="插入 transform" width="980px" append-to-body>
    <div class="layout">
      <ul class="list">
        <li v-for="t in TEMPLATES" :key="t.plugin" :class="{ active: t.plugin === picked.plugin }" @click="pick(t)">
          <div class="label">{{ t.label }}</div>
          <div class="plugin">{{ t.plugin }}</div>
        </li>
      </ul>
      <div class="detail">
        <div class="help">{{ picked.help }}。{{ common }}。</div>
        <el-input v-model="text" type="textarea" :rows="11" class="code" spellcheck="false" />
        <div class="opt-title">全部参数（来自 SeaTunnel 集群）</div>
        <el-alert v-if="optionsError" :title="optionsError" type="warning" :closable="false" />
        <el-table v-else v-loading="loading" :data="options" max-height="220" size="small" class="opts">
          <el-table-column prop="key" label="参数" width="190">
            <template #default="{ row }"><code class="mono">{{ row.key }}</code><span v-if="row.required" class="req">必填</span></template>
          </el-table-column>
          <el-table-column label="默认值" width="120">
            <template #default="{ row }">{{ row.defaultValue ?? '-' }}</template>
          </el-table-column>
          <el-table-column prop="description" label="说明（SeaTunnel 原文）" min-width="300" show-overflow-tooltip />
        </el-table>
      </div>
    </div>
    <template #footer>
      <span class="tip">脚本里没有 transform 块时，会在 sink 前面新建一个；已有时插到光标处</span>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="insert">插入</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr);
  gap: 16px;
}

.list {
  margin: 0;
  padding: 0;
  list-style: none;
  border-right: 1px solid var(--el-border-color-lighter);
}

.list li {
  padding: 8px 12px;
  border-left: 3px solid transparent;
  cursor: pointer;
}

.list li:hover {
  background: #f5f8fc;
}

.list li.active {
  background: #e8f1ff;
  border-left-color: var(--el-color-primary);
}

.label {
  font-size: 14px;
}

.plugin {
  color: var(--el-text-color-secondary);
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 11px;
}

.help {
  margin-bottom: 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.code :deep(textarea) {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12px;
}

.opt-title {
  margin: 12px 0 6px;
  font-weight: 500;
}

.mono {
  padding: 0;
  background: none;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12px;
}

.req {
  margin-left: 6px;
  color: var(--el-color-danger);
  font-size: 11px;
}

.tip {
  margin-right: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>

<script setup lang="ts">
/**
 * Keys for the wizard's field encryption (docs/design.md §7.11), admins only. A key is generated or
 * imported, stored encrypted, and never shown again; it can be disabled but not deleted.
 */
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'

import { encryptKeyApi, type EncryptKeyView } from '@/api/console'
import PageHeader from '@/components/PageHeader.vue'
import { formatTime } from '@/utils/format'

const keys = ref<EncryptKeyView[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    keys.value = await encryptKeyApi.list()
  } finally {
    loading.value = false
  }
}

const dialog = reactive({
  visible: false,
  editing: null as EncryptKeyView | null,
  name: '',
  description: '',
  algorithm: 'AES_GCM',
  source: 'generate' as 'generate' | 'import',
  bits: 256,
  importedKey: '',
  saving: false,
})

function openCreate() {
  Object.assign(dialog, { visible: true, editing: null, name: '', description: '', algorithm: 'AES_GCM', source: 'generate', bits: 256, importedKey: '' })
}

function openEdit(k: EncryptKeyView) {
  Object.assign(dialog, { visible: true, editing: k, name: k.name, description: k.description ?? '' })
}

async function save() {
  dialog.saving = true
  try {
    if (dialog.editing) {
      await encryptKeyApi.update(dialog.editing.id, { name: dialog.name, description: dialog.description || null, enabled: dialog.editing.enabled })
    } else {
      await encryptKeyApi.create({
        name: dialog.name,
        description: dialog.description || undefined,
        algorithm: dialog.algorithm,
        bits: dialog.source === 'generate' ? dialog.bits : undefined,
        importedKey: dialog.source === 'import' ? dialog.importedKey : undefined,
      })
    }
    ElMessage.success('已保存')
    dialog.visible = false
    await load()
  } finally {
    dialog.saving = false
  }
}

async function toggle(k: EncryptKeyView) {
  await encryptKeyApi.update(k.id, { name: k.name, description: k.description, enabled: !k.enabled })
  ElMessage.success(k.enabled ? '已停用，用它的任务下次运行会失败，需要换一个密钥' : '已启用')
  await load()
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <PageHeader title="加密密钥" description="任务向导里的字段加密用这些密钥。密钥加密保存，创建后不能再查看；只能停用，不能删除。">
      <el-button type="primary" :icon="Plus" @click="openCreate">新建密钥</el-button>
    </PageHeader>
    <el-table v-loading="loading" :data="keys">
      <el-table-column prop="name" label="名称" min-width="180" />
      <el-table-column prop="description" label="说明" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.description || '-' }}</template>
      </el-table-column>
      <el-table-column label="算法" width="150">
        <template #default="{ row }">{{ row.algorithm }} · {{ row.keyBits }} 位</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" disable-transitions>{{ row.enabled ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建人" width="120">
        <template #default="{ row }">{{ row.createdByName ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link :type="row.enabled ? 'danger' : 'primary'" @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog v-model="dialog.visible" :title="dialog.editing ? '编辑密钥' : '新建密钥'" width="560px">
    <el-form label-width="96px">
      <el-form-item label="名称" required>
        <el-input v-model="dialog.name" maxlength="64" placeholder="如 用户手机号" />
      </el-form-item>
      <el-form-item label="说明">
        <el-input v-model="dialog.description" maxlength="512" placeholder="用途、负责人等" />
      </el-form-item>
      <template v-if="!dialog.editing">
        <el-form-item label="算法">
          <el-radio-group v-model="dialog.algorithm">
            <el-radio value="AES_GCM">AES-GCM（推荐）</el-radio>
            <el-radio value="AES_CBC">AES-CBC</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="密钥">
          <el-radio-group v-model="dialog.source">
            <el-radio value="generate">自动生成</el-radio>
            <el-radio value="import">导入已有密钥</el-radio>
          </el-radio-group>
          <el-select v-if="dialog.source === 'generate'" v-model="dialog.bits" class="bits">
            <el-option label="256 位" :value="256" /><el-option label="192 位" :value="192" /><el-option label="128 位" :value="128" />
          </el-select>
          <el-input v-else v-model="dialog.importedKey" type="password" show-password class="imported"
            placeholder="Base64 编码，解码后 16 / 24 / 32 字节" />
        </el-form-item>
        <el-alert type="warning" :closable="false" show-icon
          title="密钥保存后不能再查看。如果以后需要在别的系统里解密这些数据，请用自己保管的密钥「导入」，不要用自动生成。" />
      </template>
    </el-form>
    <template #footer>
      <el-button @click="dialog.visible = false">取消</el-button>
      <el-button type="primary" :loading="dialog.saving" :disabled="!dialog.name.trim() || (!dialog.editing && dialog.source === 'import' && !dialog.importedKey.trim())" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.bits {
  width: 140px;
  margin-left: 12px;
}

.imported {
  margin-top: 8px;
}
</style>

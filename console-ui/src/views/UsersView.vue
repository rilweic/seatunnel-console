<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'

import { userApi, type AccountView } from '@/api/console'
import PageHeader from '@/components/PageHeader.vue'
import { useUserStore } from '@/stores/user'
import { formatTime } from '@/utils/format'

const store = useUserStore()
const users = ref<AccountView[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    users.value = await userApi.list()
  } finally {
    loading.value = false
  }
}

/** 12 characters, letters and digits, so it always passes the password policy. */
function randomPassword(): string {
  const letters = 'abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ'
  const digits = '23456789'
  const all = letters + digits
  const pick = (set: string) => set[crypto.getRandomValues(new Uint32Array(1))[0]! % set.length]!
  const chars = [pick(letters), pick(digits), ...Array.from({ length: 10 }, () => pick(all))]
  return chars.sort(() => crypto.getRandomValues(new Uint32Array(1))[0]! - 2 ** 31).join('')
}

const createDialog = reactive({ visible: false, username: '', displayName: '', role: 'USER' as 'ADMIN' | 'USER', password: '', saving: false })

function openCreate() {
  Object.assign(createDialog, { visible: true, username: '', displayName: '', role: 'USER', password: randomPassword() })
}

async function create() {
  createDialog.saving = true
  try {
    await userApi.create({
      username: createDialog.username,
      displayName: createDialog.displayName || undefined,
      role: createDialog.role,
      password: createDialog.password,
    })
    createDialog.visible = false
    ElMessage.success('用户已创建，请把初始密码告诉对方，首次登录时需要修改')
    load()
  } finally {
    createDialog.saving = false
  }
}

const editDialog = reactive({ visible: false, user: null as AccountView | null, displayName: '', role: 'USER' as 'ADMIN' | 'USER', enabled: true, saving: false })

function openEdit(u: AccountView) {
  Object.assign(editDialog, { visible: true, user: u, displayName: u.displayName ?? '', role: u.role, enabled: u.enabled })
}

async function saveEdit() {
  editDialog.saving = true
  try {
    await userApi.update(editDialog.user!.id, { displayName: editDialog.displayName || null, role: editDialog.role, enabled: editDialog.enabled })
    editDialog.visible = false
    ElMessage.success('已保存')
    load()
  } finally {
    editDialog.saving = false
  }
}

async function toggleEnabled(u: AccountView) {
  if (u.enabled) {
    try {
      await ElMessageBox.confirm(`禁用后「${u.username}」不能再登录，名下的任务和调度不受影响。`, '禁用用户', {
        type: 'warning',
        confirmButtonText: '禁用',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }
  await userApi.update(u.id, { displayName: u.displayName, role: u.role, enabled: !u.enabled })
  ElMessage.success(u.enabled ? '已禁用' : '已启用')
  load()
}

const resetDialog = reactive({ visible: false, user: null as AccountView | null, password: '', saving: false })

function openReset(u: AccountView) {
  Object.assign(resetDialog, { visible: true, user: u, password: randomPassword() })
}

async function reset() {
  resetDialog.saving = true
  try {
    await userApi.resetPassword(resetDialog.user!.id, resetDialog.password)
    resetDialog.visible = false
    ElMessage.success('密码已重置，对方下次登录时需要修改；如果账号因输错被锁定，也已解锁')
    load()
  } finally {
    resetDialog.saving = false
  }
}

const transferDialog = reactive({ visible: false, user: null as AccountView | null, toUserId: '', saving: false })
const transferTargets = computed(() => users.value.filter((u) => u.enabled && u.id !== transferDialog.user?.id))

function openTransfer(u: AccountView) {
  Object.assign(transferDialog, { visible: true, user: u, toUserId: '' })
}

async function transfer() {
  transferDialog.saving = true
  try {
    const moved = await userApi.transfer(transferDialog.user!.id, transferDialog.toUserId)
    transferDialog.visible = false
    ElMessage.success(`已转移：项目 ${moved.projects} 个、数据源 ${moved.datasources} 个、任务 ${moved.jobs} 个`)
    load()
  } finally {
    transferDialog.saving = false
  }
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <PageHeader title="用户管理" description="管理员新建用户并设置初始密码，用户首次登录时必须修改。用户不能删除，只能禁用；离职或交接时，可以把名下的项目、数据源和任务整体转给另一个用户。">
      <el-button type="primary" :icon="Plus" @click="openCreate">新建用户</el-button>
    </PageHeader>
    <el-table v-loading="loading" :data="users">
      <el-table-column label="用户名" min-width="140">
        <template #default="{ row }">
          {{ row.username }}<span v-if="row.id === store.user?.id" class="me">（我）</span>
        </template>
      </el-table-column>
      <el-table-column prop="displayName" label="显示名" min-width="120" />
      <el-table-column label="角色" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.role === 'ADMIN' ? 'warning' : 'info'">{{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '已禁用' }}</el-tag>
          <el-tag v-if="row.mustChangePassword" size="small" type="warning" class="gap">待改密码</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="名下（项目 / 数据源 / 任务）" width="190">
        <template #default="{ row }">{{ row.projects }} / {{ row.datasources }} / {{ row.jobs }}</template>
      </el-table-column>
      <el-table-column label="最近登录" width="170">
        <template #default="{ row }">{{ formatTime(row.lastLoginAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="openReset(row)">重置密码</el-button>
          <el-button link type="primary" :disabled="row.projects + row.datasources + row.jobs === 0" @click="openTransfer(row)">转移</el-button>
          <el-button link :type="row.enabled ? 'danger' : 'success'" :disabled="row.id === store.user?.id" @click="toggleEnabled(row)">
            {{ row.enabled ? '禁用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog v-model="createDialog.visible" title="新建用户" width="480px">
    <el-form label-width="90px">
      <el-form-item label="用户名" required>
        <el-input v-model="createDialog.username" placeholder="字母、数字、_ . -" maxlength="64" />
      </el-form-item>
      <el-form-item label="显示名">
        <el-input v-model="createDialog.displayName" maxlength="64" />
      </el-form-item>
      <el-form-item label="角色">
        <el-radio-group v-model="createDialog.role">
          <el-radio value="USER">普通用户</el-radio>
          <el-radio value="ADMIN">管理员</el-radio>
        </el-radio-group>
        <div class="tip">普通用户只能修改和运行自己创建的项目、数据源和任务，其他人的只能查看</div>
      </el-form-item>
      <el-form-item label="初始密码" required>
        <el-input v-model="createDialog.password" class="mono">
          <template #append><el-button @click="createDialog.password = randomPassword()">换一个</el-button></template>
        </el-input>
        <div class="tip">请复制后告诉对方；首次登录时必须修改</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createDialog.visible = false">取消</el-button>
      <el-button type="primary" :loading="createDialog.saving" @click="create">创建</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="editDialog.visible" :title="`编辑 ${editDialog.user?.username}`" width="440px">
    <el-form label-width="80px">
      <el-form-item label="显示名">
        <el-input v-model="editDialog.displayName" maxlength="64" />
      </el-form-item>
      <el-form-item label="角色">
        <el-radio-group v-model="editDialog.role">
          <el-radio value="USER">普通用户</el-radio>
          <el-radio value="ADMIN">管理员</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="启用">
        <el-switch v-model="editDialog.enabled" :disabled="editDialog.user?.id === store.user?.id" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="editDialog.visible = false">取消</el-button>
      <el-button type="primary" :loading="editDialog.saving" @click="saveEdit">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="resetDialog.visible" :title="`重置 ${resetDialog.user?.username} 的密码`" width="440px">
    <el-input v-model="resetDialog.password" class="mono">
      <template #append><el-button @click="resetDialog.password = randomPassword()">换一个</el-button></template>
    </el-input>
    <div class="tip">请复制后告诉对方；对方下次登录时必须修改。因输错密码被锁定的账号也会同时解锁。</div>
    <template #footer>
      <el-button @click="resetDialog.visible = false">取消</el-button>
      <el-button type="primary" :loading="resetDialog.saving" @click="reset">重置</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="transferDialog.visible" :title="`转移 ${transferDialog.user?.username} 名下的内容`" width="480px">
    <p class="tip">
      项目 {{ transferDialog.user?.projects }} 个、数据源 {{ transferDialog.user?.datasources }} 个、任务 {{ transferDialog.user?.jobs }} 个会整体转给：
    </p>
    <el-select v-model="transferDialog.toUserId" placeholder="选择接收人" class="full">
      <el-option v-for="u in transferTargets" :key="u.id" :label="`${u.username}${u.displayName ? '（' + u.displayName + '）' : ''}`" :value="u.id" />
    </el-select>
    <p class="tip">任务的运行记录和调度跟着任务一起转移。转移后原用户不能再修改这些内容。</p>
    <template #footer>
      <el-button @click="transferDialog.visible = false">取消</el-button>
      <el-button type="primary" :disabled="!transferDialog.toUserId" :loading="transferDialog.saving" @click="transfer">转移</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.me {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.gap {
  margin-left: 6px;
}

.tip {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.mono :deep(input) {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
}

.full {
  width: 100%;
}
</style>

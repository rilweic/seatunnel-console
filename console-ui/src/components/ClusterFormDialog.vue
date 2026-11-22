<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { clustersApi, type ClusterRequest, type ClusterTestResult, type ClusterView, type EngineType } from '@/api/clusters'

const props = defineProps<{ cluster: ClusterView | null }>()
const emit = defineEmits<{ saved: [cluster: ClusterView] }>()
const visible = defineModel<boolean>({ required: true })

const form = reactive({
  name: '',
  engineType: 'FLINK' as EngineType,
  description: '',
  restUrls: '',
  username: '',
  password: '',
  flinkVersion: '1.20.1',
  flinkHome: '',
  seatunnelHome: '',
  javaHome: '',
  isDefault: false,
})

const saving = ref(false)
const testing = ref(false)
const testResult = ref<ClusterTestResult | null>(null)
const isEdit = computed(() => props.cluster != null)

watch(visible, (open) => {
  if (!open) return
  testResult.value = null
  if (props.cluster) {
    Object.assign(form, {
      name: props.cluster.name,
      engineType: props.cluster.engineType,
      description: props.cluster.description ?? '',
      restUrls: props.cluster.restUrls,
      username: props.cluster.username ?? '',
      password: '',
      flinkVersion: props.cluster.flinkVersion ?? '1.20.1',
      flinkHome: props.cluster.flinkHome ?? '',
      seatunnelHome: props.cluster.seatunnelHome ?? '',
      javaHome: props.cluster.javaHome ?? '',
      isDefault: props.cluster.isDefault,
    })
  } else {
    Object.assign(form, {
      name: '',
      engineType: 'FLINK',
      description: '',
      restUrls: '',
      username: '',
      password: '',
      flinkVersion: '1.20.1',
      flinkHome: '',
      seatunnelHome: '',
      javaHome: '',
      isDefault: false,
    })
  }
})

function buildRequest(): ClusterRequest {
  return {
    name: form.name.trim(),
    engineType: form.engineType,
    description: form.description.trim() || undefined,
    restUrls: form.restUrls.trim(),
    username: form.engineType === 'ZETA' ? form.username.trim() || undefined : undefined,
    password: form.engineType === 'ZETA' && form.password ? form.password : undefined,
    flinkVersion: form.engineType === 'FLINK' ? form.flinkVersion.trim() || undefined : undefined,
    flinkHome: form.engineType === 'FLINK' ? form.flinkHome.trim() || undefined : undefined,
    seatunnelHome: form.engineType === 'FLINK' ? form.seatunnelHome.trim() || undefined : undefined,
    javaHome: form.engineType === 'FLINK' ? form.javaHome.trim() || undefined : undefined,
    isDefault: form.isDefault,
  }
}

async function test() {
  if (!form.name.trim() || !form.restUrls.trim()) {
    ElMessage.warning('请先填写集群名称和 REST 地址')
    return
  }
  testing.value = true
  testResult.value = null
  try {
    const res = await clustersApi.test(buildRequest(), props.cluster?.id)
    testResult.value = res
    if (res.ok && res.version && form.engineType === 'FLINK') {
      form.flinkVersion = res.version
    }
  } finally {
    testing.value = false
  }
}

async function save() {
  if (!form.name.trim() || !form.restUrls.trim()) {
    ElMessage.warning('请填写集群名称和 REST 地址')
    return
  }
  saving.value = true
  try {
    const saved = props.cluster
      ? await clustersApi.update(props.cluster.id, buildRequest())
      : await clustersApi.create(buildRequest())
    ElMessage.success(props.cluster ? '已保存集群配置' : '已添加集群')
    visible.value = false
    emit('saved', saved)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="isEdit ? '编辑集群' : '登记集群'" width="620px">
    <el-form label-width="140px">
      <el-form-item label="集群名称" required>
        <el-input v-model="form.name" maxlength="100" placeholder="例如：本地 Flink 1.20 集群" />
      </el-form-item>
      <el-form-item label="引擎类型" required>
        <el-radio-group v-model="form.engineType" :disabled="isEdit">
          <el-radio value="ZETA">SeaTunnel Zeta</el-radio>
          <el-radio value="FLINK">Apache Flink</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item :label="form.engineType === 'FLINK' ? 'REST 地址' : 'Master 地址'" required>
        <el-input
          v-model="form.restUrls"
          :placeholder="form.engineType === 'FLINK' ? 'http://localhost:8082' : 'http://localhost:8080（多个用逗号分隔）'"
        />
      </el-form-item>

      <template v-if="form.engineType === 'ZETA'">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="Basic 认证用户名（可选）" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="cluster?.hasPassword ? '留空则保持原密码不变' : 'Basic 认证密码（可选）'"
          />
        </el-form-item>
      </template>

      <template v-else>
        <el-form-item label="Flink 版本">
          <el-input v-model="form.flinkVersion" placeholder="例如 1.20.1（不支持 1.19；测试连接可自动识别）" />
        </el-form-item>
        <el-form-item label="FLINK_HOME" required>
          <el-input v-model="form.flinkHome" placeholder="console 本机同版本 Flink 客户端目录，如 /opt/flink" />
        </el-form-item>
        <el-form-item label="SeaTunnel 目录" required>
          <el-input v-model="form.seatunnelHome" placeholder="console 本机精简 SeaTunnel 发行包目录，如 /opt/seatunnel" />
        </el-form-item>
        <el-form-item label="JAVA_HOME">
          <el-input v-model="form.javaHome" placeholder="可选；为空时沿用 console 所在环境" />
        </el-form-item>
      </template>

      <el-form-item label="说明">
        <el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" />
      </el-form-item>
      <el-form-item label="默认集群">
        <el-switch v-model="form.isDefault" />
        <span class="tip">新建任务时默认选中</span>
      </el-form-item>
    </el-form>

    <div v-if="testResult" class="test-box">
      <el-alert
        :type="testResult.ok ? (testResult.warnings.length ? 'warning' : 'success') : 'error'"
        :title="testResult.ok ? `连接成功（版本 ${testResult.version ?? '未知'}，响应 ${testResult.latencyMillis} ms）` : `连接失败：${testResult.error}`"
        :closable="false"
        show-icon
      >
        <ul v-if="testResult.warnings.length" class="warn-list">
          <li v-for="(w, i) in testResult.warnings" :key="i">{{ w }}</li>
        </ul>
      </el-alert>
    </div>

    <template #footer>
      <el-button :loading="testing" @click="test">测试连接</el-button>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tip {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.test-box {
  margin-top: 8px;
}

.warn-list {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.6;
}
</style>

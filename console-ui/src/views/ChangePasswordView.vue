<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { authApi } from '@/api/console'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

const forced = computed(() => store.user?.mustChangePassword === true)
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const saving = ref(false)

const policyOk = computed(() => form.newPassword.length >= 8 && /[A-Za-z]/.test(form.newPassword) && /\d/.test(form.newPassword))

async function submit() {
  if (!policyOk.value) {
    ElMessage.warning('新密码至少 8 位，并且要同时包含字母和数字')
    return
  }
  if (form.newPassword !== form.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  saving.value = true
  try {
    store.set(await authApi.changePassword(form.oldPassword, form.newPassword))
    ElMessage.success('密码已修改')
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect !== '/change-password' ? route.query.redirect : '/jobs'
    router.replace(redirect)
  } finally {
    saving.value = false
  }
}

async function logout() {
  await store.logout()
  router.replace('/login')
}
</script>

<template>
  <div class="page">
    <div class="card">
      <div class="title">{{ forced ? '请先修改初始密码' : '修改密码' }}</div>
      <div class="sub">
        {{ forced ? '这是管理员为你设置的密码，修改后才能继续使用。' : `当前账号：${store.user?.username}` }}
      </div>
      <el-form label-position="top" size="large" @submit.prevent="submit">
        <el-form-item :label="forced ? '初始密码' : '原密码'">
          <el-input v-model="form.oldPassword" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="form.newPassword" type="password" show-password autocomplete="new-password" />
          <div class="hint" :class="{ ok: policyOk }">至少 8 位，同时包含字母和数字</div>
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="form.confirm" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="saving" class="submit">修改密码</el-button>
      </el-form>
      <div class="foot">
        <el-link v-if="forced" underline="never" @click="logout">退出登录</el-link>
        <el-link v-else underline="never" @click="router.back()">返回</el-link>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 16px;
  background: var(--el-bg-color-page);
}

.card {
  width: 400px;
  max-width: 100%;
  padding: 36px 36px 24px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.08);
  box-sizing: border-box;
}

.title {
  font-size: 20px;
  font-weight: 600;
}

.sub {
  margin: 6px 0 24px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.hint.ok {
  color: var(--el-color-success);
}

.submit {
  width: 100%;
  margin-top: 8px;
}

.foot {
  margin-top: 16px;
  text-align: center;
}
</style>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function submit() {
  if (!form.username || !form.password) {
    return
  }
  loading.value = true
  try {
    await userStore.login(form.username, form.password)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/jobs'
    router.replace(redirect)
  } catch {
    // the error message is shown by the http interceptor
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <span class="logo">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"
            stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M2 12c2.5 0 2.5-3 5-3s2.5 3 5 3 2.5-3 5-3 2.5 3 5 3" />
            <path d="M2 18c2.5 0 2.5-3 5-3s2.5 3 5 3 2.5-3 5-3 2.5 3 5 3" />
            <path d="M2 6c2.5 0 2.5-3 5-3s2.5 3 5 3 2.5-3 5-3 2.5 3 5 3" />
          </svg>
        </span>
        <div>
          <div class="name">SeaTunnel Console</div>
          <div class="sub">数据集成任务管理平台</div>
        </div>
      </div>
      <el-form label-position="top" size="large" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="username" autofocus placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password autocomplete="current-password" placeholder="请输入密码" />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading" class="submit">登录</el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 16px;
  background:
    radial-gradient(circle at 15% 20%, rgba(22, 119, 255, 0.14), transparent 40%),
    radial-gradient(circle at 85% 80%, rgba(59, 110, 245, 0.16), transparent 45%),
    #f0f3f8;
}

.login-card {
  width: 400px;
  max-width: 100%;
  padding: 40px 36px 32px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.08), 0 9px 28px 8px rgba(0, 0, 0, 0.04);
  box-sizing: border-box;
}

.brand {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 32px;
}

.logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 10px;
  color: #fff;
  background: linear-gradient(135deg, #4096ff, #1677ff);
}

.name {
  font-size: 20px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.sub {
  margin-top: 2px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.submit {
  width: 100%;
  margin-top: 8px;
}
</style>

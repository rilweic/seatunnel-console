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
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
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
    <div class="water-background" aria-hidden="true">
      <svg viewBox="0 0 1600 1000" preserveAspectRatio="xMidYMid slice" fill="none">
        <defs>
          <linearGradient id="water-back" x1="240" y1="440" x2="1280" y2="1060" gradientUnits="userSpaceOnUse">
            <stop stop-color="#91caff" stop-opacity=".12" />
            <stop offset="1" stop-color="#4096ff" stop-opacity=".34" />
          </linearGradient>
          <linearGradient id="water-middle" x1="300" y1="420" x2="1180" y2="1100" gradientUnits="userSpaceOnUse">
            <stop stop-color="#69b1ff" stop-opacity=".08" />
            <stop offset="1" stop-color="#1677ff" stop-opacity=".24" />
          </linearGradient>
          <linearGradient id="water-front" x1="460" y1="620" x2="1040" y2="1160" gradientUnits="userSpaceOnUse">
            <stop stop-color="#bae0ff" stop-opacity=".14" />
            <stop offset="1" stop-color="#0958d9" stop-opacity=".2" />
          </linearGradient>
          <linearGradient id="water-stream" x1="0" y1="0" x2="1600" y2="300" gradientUnits="userSpaceOnUse">
            <stop stop-color="#fff" stop-opacity="0" />
            <stop offset=".5" stop-color="#79adff" stop-opacity=".22" />
            <stop offset="1" stop-color="#fff" stop-opacity="0" />
          </linearGradient>
        </defs>
        <path class="water-stream" d="M-300 190C80-100 450 290 830 70S1430-30 1900 220" stroke="url(#water-stream)" stroke-width="170" />
        <path class="water-current water-back" d="M-240 780C120 520 420 1050 760 730C1100 410 1350 760 1840 350V1200H-240Z" fill="url(#water-back)" />
        <path class="water-current water-middle" d="M-260 980C180 600 460 920 820 610C1180 300 1450 550 1860 180V1200H-260Z" fill="url(#water-middle)" />
        <path class="water-current water-front" d="M-260 1090C160 810 500 1040 860 790C1220 540 1500 800 1860 490V1200H-260Z" fill="url(#water-front)" />
        <path class="water-ripple ripple-one" d="M-180 790C160 550 420 1010 760 730C1100 450 1370 720 1780 430" stroke="#fff" stroke-opacity=".32" stroke-width="2" />
        <path class="water-ripple ripple-two" d="M-180 997C190 680 470 930 820 650C1170 370 1450 600 1780 320" stroke="#fff" stroke-opacity=".24" stroke-width="1.5" />
      </svg>
    </div>

    <main class="login-panel">
      <section class="intro">
        <div class="brand">
          <span class="logo">
            <svg viewBox="0 0 24 24" width="25" height="25" fill="none" stroke="currentColor" stroke-width="1.8"
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

        <div class="intro-copy">
          <div class="eyebrow">DATA INTEGRATION</div>
          <h1>让数据流动<br /><span>更简单、更可靠</span></h1>
          <p>从任务编排到运行监控，一站式管理你的数据集成工作。</p>
        </div>

        <div class="flow-canvas" aria-hidden="true">
          <svg class="flow-lines" viewBox="0 0 360 150" fill="none">
            <defs>
              <linearGradient id="flow-gradient" x1="45" y1="75" x2="315" y2="75" gradientUnits="userSpaceOnUse">
                <stop stop-color="#91caff" stop-opacity=".35" />
                <stop offset=".5" stop-color="#fff" stop-opacity=".9" />
                <stop offset="1" stop-color="#91caff" stop-opacity=".35" />
              </linearGradient>
            </defs>
            <path d="M62 45C118 45 118 75 174 75" stroke="url(#flow-gradient)" stroke-width="1.5" stroke-dasharray="5 6" />
            <path d="M62 105C118 105 118 75 174 75" stroke="url(#flow-gradient)" stroke-width="1.5" stroke-dasharray="5 6" />
            <path d="M198 75H302" stroke="url(#flow-gradient)" stroke-width="1.5" stroke-dasharray="5 6" />
            <circle cx="118" cy="57" r="3" fill="#fff" />
            <circle cx="249" cy="75" r="3" fill="#fff" />
          </svg>
          <div class="flow-node source-one">
            <span class="node-dot"></span>多源数据
          </div>
          <div class="flow-node source-two">
            <span class="node-dot"></span>实时同步
          </div>
          <div class="flow-node engine">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8"
              stroke-linecap="round" aria-hidden="true">
              <path d="M3 8c3 0 3-3 6-3s3 3 6 3 3-3 6-3M3 16c3 0 3-3 6-3s3 3 6 3 3-3 6-3" />
            </svg>
            SeaTunnel
          </div>
          <div class="flow-node target">
            <span class="node-dot success"></span>目标数据源
          </div>
        </div>

        <div class="features">
          <span>任务编排</span>
          <span>运行监控</span>
          <span>补数管理</span>
        </div>
      </section>

      <section class="form-section">
        <div class="mobile-brand">
          <span class="logo">
            <svg viewBox="0 0 24 24" width="23" height="23" fill="none" stroke="currentColor" stroke-width="1.8"
              stroke-linecap="round" aria-hidden="true">
              <path d="M2 8c2.5 0 2.5-3 5-3s2.5 3 5 3 2.5-3 5-3 2.5 3 5 3M2 16c2.5 0 2.5-3 5-3s2.5 3 5 3 2.5-3 5-3 2.5 3 5 3" />
            </svg>
          </span>
          <span>SeaTunnel Console</span>
        </div>

        <div class="form-heading">
          <div class="welcome">欢迎回来</div>
          <h2>登录你的账号</h2>
          <p>继续管理数据集成任务</p>
        </div>

        <el-form class="login-form" label-position="top" size="large" @submit.prevent="submit">
          <el-form-item label="用户名">
            <el-input v-model="form.username" autocomplete="username" autofocus placeholder="请输入用户名">
              <template #prefix>
                <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.8"
                  stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                  <circle cx="12" cy="8" r="4" /><path d="M4 21a8 8 0 0 1 16 0" />
                </svg>
              </template>
            </el-input>
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="form.password" type="password" show-password autocomplete="current-password" placeholder="请输入密码">
              <template #prefix>
                <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.8"
                  stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                  <rect x="4" y="10" width="16" height="11" rx="2" /><path d="M8 10V7a4 4 0 0 1 8 0v3" />
                </svg>
              </template>
            </el-input>
          </el-form-item>
          <el-button type="primary" native-type="submit" :loading="loading" class="submit">
            {{ loading ? '正在登录' : '登录' }}
          </el-button>
        </el-form>

        <div class="form-footer"><span></span>集中管理 · 高效协同 · 稳定运行</div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  min-height: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  place-items: center;
  box-sizing: border-box;
  padding: 40px 24px;
  overflow: hidden;
  background:
    linear-gradient(rgba(22, 119, 255, 0.025) 1px, transparent 1px),
    linear-gradient(90deg, rgba(22, 119, 255, 0.025) 1px, transparent 1px),
    #f5f8fc;
  background-size: 32px 32px;
}

.water-background {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.water-background svg {
  display: block;
  width: 100%;
  height: 100%;
}

.water-current,
.water-ripple,
.water-stream {
  transform-box: fill-box;
  transform-origin: center;
  will-change: transform;
}

.water-back {
  animation: water-sway 17s ease-in-out infinite alternate;
}

.water-middle {
  animation: water-sway 13s ease-in-out -4s infinite alternate-reverse;
}

.water-front {
  animation: water-sway 10s ease-in-out -2s infinite alternate;
}

.water-stream {
  animation: stream-sway 20s ease-in-out infinite alternate;
}

.ripple-one {
  animation: water-sway 17s ease-in-out infinite alternate;
}

.ripple-two {
  animation: water-sway 13s ease-in-out -4s infinite alternate-reverse;
}

@keyframes water-sway {
  from {
    transform: translate3d(-2%, 9px, 0) scale(1.03);
  }
  to {
    transform: translate3d(2%, -12px, 0) scale(1.07);
  }
}

@keyframes stream-sway {
  from {
    transform: translate3d(-3%, -12px, 0) rotate(-2deg);
  }
  to {
    transform: translate3d(3%, 18px, 0) rotate(2deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .water-current,
  .water-ripple,
  .water-stream {
    animation: none;
  }
}

.login-panel {
  position: relative;
  width: 920px;
  max-width: 100%;
  min-height: 560px;
  display: flex;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.98);
  border: 1px solid rgba(255, 255, 255, 0.9);
  border-radius: 20px;
  box-shadow: 0 24px 70px rgba(31, 68, 122, 0.13), 0 4px 16px rgba(31, 68, 122, 0.05);
  animation: panel-in 0.45s ease-out;
}

.intro {
  position: relative;
  width: 43%;
  flex: none;
  box-sizing: border-box;
  padding: 36px 38px 30px;
  overflow: hidden;
  color: #fff;
  background:
    radial-gradient(circle at 85% 15%, rgba(145, 202, 255, 0.28), transparent 35%),
    radial-gradient(circle at 10% 90%, rgba(77, 214, 255, 0.15), transparent 38%),
    linear-gradient(145deg, #0958d9 0%, #1677ff 55%, #3b6ef5 100%);
}

.intro::before,
.intro::after {
  content: '';
  position: absolute;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 50%;
  pointer-events: none;
}

.intro::before {
  width: 300px;
  height: 300px;
  top: -180px;
  right: -150px;
}

.intro::after {
  width: 220px;
  height: 220px;
  right: -120px;
  bottom: -100px;
}

.brand,
.mobile-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.brand {
  position: relative;
  z-index: 1;
}

.logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  flex: none;
  border-radius: 12px;
  color: #fff;
  background: rgba(255, 255, 255, 0.16);
  border: 1px solid rgba(255, 255, 255, 0.2);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.15);
  backdrop-filter: blur(8px);
}

.name {
  font-size: 18px;
  line-height: 26px;
  font-weight: 600;
  letter-spacing: 0.1px;
}

.sub {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.68);
}

.intro-copy {
  position: relative;
  z-index: 1;
  margin-top: 54px;
}

.eyebrow {
  margin-bottom: 12px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 2.4px;
  color: rgba(255, 255, 255, 0.62);
}

.intro-copy h1 {
  margin: 0;
  font-size: 31px;
  line-height: 1.45;
  font-weight: 600;
  letter-spacing: 1px;
}

.intro-copy h1 span {
  color: #d6e8ff;
}

.intro-copy p {
  max-width: 290px;
  margin: 16px 0 0;
  font-size: 13px;
  line-height: 1.9;
  color: rgba(255, 255, 255, 0.7);
}

.flow-canvas {
  position: relative;
  height: 142px;
  margin-top: 24px;
}

.flow-lines {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.flow-node {
  position: absolute;
  display: flex;
  align-items: center;
  gap: 7px;
  box-sizing: border-box;
  height: 32px;
  padding: 0 11px;
  font-size: 11px;
  white-space: nowrap;
  color: rgba(255, 255, 255, 0.88);
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.16);
  border-radius: 8px;
  box-shadow: 0 5px 15px rgba(0, 40, 110, 0.08);
  backdrop-filter: blur(6px);
}

.source-one {
  top: 22px;
  left: 0;
}

.source-two {
  top: 82px;
  left: 0;
}

.engine {
  top: 52px;
  left: 50%;
  height: 46px;
  padding: 0 14px;
  transform: translateX(-50%);
  font-size: 12px;
  font-weight: 500;
  color: #1677ff;
  background: #fff;
  border: 0;
  border-radius: 12px;
  box-shadow: 0 8px 24px rgba(0, 38, 107, 0.2);
}

.target {
  top: 59px;
  right: 0;
}

.node-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #91caff;
  box-shadow: 0 0 0 3px rgba(145, 202, 255, 0.12);
}

.node-dot.success {
  background: #95de64;
  box-shadow: 0 0 0 3px rgba(149, 222, 100, 0.12);
}

.features {
  display: flex;
  gap: 20px;
  margin-top: 22px;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.62);
}

.features span {
  display: flex;
  align-items: center;
  gap: 6px;
}

.features span::before {
  content: '';
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.7);
}

.form-section {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  box-sizing: border-box;
  padding: 48px 58px 36px;
}

.mobile-brand {
  display: none;
}

.form-heading {
  margin-bottom: 30px;
}

.welcome {
  margin-bottom: 8px;
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 1px;
  color: var(--el-color-primary);
}

.form-heading h2 {
  margin: 0;
  font-size: 26px;
  line-height: 1.4;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.form-heading p {
  margin: 9px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.login-form :deep(.el-form-item) {
  margin-bottom: 22px;
}

.login-form :deep(.el-form-item__label) {
  padding-bottom: 7px;
  line-height: 20px;
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-regular);
}

.login-form :deep(.el-input__wrapper) {
  min-height: 44px;
  padding: 2px 13px;
  border-radius: 8px;
  background: #fbfcfe;
  box-shadow: 0 0 0 1px #e2e8f0 inset;
  transition: background 0.2s, box-shadow 0.2s;
}

.login-form :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--el-color-primary-light-5) inset;
}

.login-form :deep(.el-input__wrapper.is-focus) {
  background: #fff;
  box-shadow: 0 0 0 1px var(--el-color-primary) inset, 0 0 0 3px rgba(22, 119, 255, 0.08);
}

.login-form :deep(.el-input__prefix) {
  margin-right: 8px;
  color: #a0aec0;
}

.submit {
  width: 100%;
  height: 44px;
  margin-top: 5px;
  font-size: 15px;
  letter-spacing: 3px;
  border: 0;
  border-radius: 8px;
  background: linear-gradient(135deg, #4096ff, #1677ff);
  box-shadow: 0 6px 16px rgba(22, 119, 255, 0.22);
  transition: transform 0.2s, box-shadow 0.2s;
}

.submit:hover {
  background: linear-gradient(135deg, #55a2ff, #2683ff);
  box-shadow: 0 8px 20px rgba(22, 119, 255, 0.28);
  transform: translateY(-1px);
}

.submit:active {
  transform: translateY(0);
}

.form-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  margin-top: 30px;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}

.form-footer span {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #95de64;
}

@keyframes panel-in {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 800px) {
  .login-page {
    padding: 24px 16px;
  }

  .login-panel {
    width: 440px;
    min-height: 0;
    border-radius: 16px;
  }

  .intro {
    display: none;
  }

  .form-section {
    padding: 34px 34px 28px;
  }

  .mobile-brand {
    display: flex;
    margin-bottom: 38px;
    font-size: 17px;
    font-weight: 600;
  }

  .mobile-brand .logo {
    width: 40px;
    height: 40px;
    background: linear-gradient(135deg, #4096ff, #1677ff);
    border: 0;
  }
}

@media (max-width: 480px) {
  .login-page {
    place-items: start center;
    padding: 20px 14px;
    overflow: auto;
  }

  .form-section {
    padding: 28px 24px 24px;
  }

  .mobile-brand {
    margin-bottom: 32px;
  }

  .form-heading {
    margin-bottom: 26px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .login-panel {
    animation: none;
  }

  .submit {
    transition: none;
  }
}
</style>

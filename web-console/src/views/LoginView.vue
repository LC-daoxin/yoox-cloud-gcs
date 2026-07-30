<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '../stores/session'

const router = useRouter()
const session = useSessionStore()
const username = ref('admin')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  loading.value = true
  try {
    await session.login(username.value.trim(), password.value)
    await router.push('/')
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-grid"></div>
    <section class="login-copy">
      <div class="brand large"><div class="brand-mark">Y</div><strong>YOOX</strong></div>
      <p class="eyebrow">CLOUD GROUND CONTROL</p>
      <h1>让每一次飞行<br><span>尽在掌控</span></h1>
      <p>统一接入无人机、遥控器与机巢，在网页端完成实时监控、远程控制、航线任务和设备运维。</p>
      <div class="feature-row">
        <span>RTSP 低延迟直播</span><span>私有化部署</span><span>全链路审计</span>
      </div>
    </section>
    <form class="login-card" @submit.prevent="submit">
      <p class="eyebrow">SECURE ACCESS</p>
      <h2>登录控制台</h2>
      <label>账号<input v-model="username" autocomplete="username" required /></label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" required /></label>
      <p v-if="error" class="form-error">{{ error }}</p>
      <button class="primary full" :disabled="loading">{{ loading ? '正在验证…' : '进入 YOOX Cloud GCS' }}</button>
      <small>首次部署默认账号仅用于初始化，请登录后立即修改密码。</small>
    </form>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { put } from './services/api'
import { useSessionStore } from './stores/session'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const showPassword = ref(false)
const oldPassword = ref('')
const newPassword = ref('')
const passwordConfirm = ref('')
const passwordError = ref('')
const passwordBusy = ref(false)

const navigation = [
  { to: '/', icon: '⌁', label: '运行总览' },
  { to: '/cockpit', icon: '✥', label: '虚拟座舱' },
  { to: '/waylines', icon: '⌁', label: '航线任务' },
  { to: '/devices', icon: '◇', label: '设备管理' },
  { to: '/media', icon: '▧', label: '媒体中心' },
  { to: '/operations', icon: '◌', label: '系统运维' }
]
const isLogin = computed(() => route.path === '/login')
const title = computed(() => String(route.meta.title ?? 'YOOX Cloud GCS'))

function signOut() {
  session.logout()
  void router.push('/login')
}

function unauthorized() {
  session.logout()
  void router.push('/login')
}

async function changePassword() {
  passwordError.value = ''
  if (newPassword.value !== passwordConfirm.value) {
    passwordError.value = '两次输入的新密码不一致'
    return
  }
  passwordBusy.value = true
  try {
    await put('/manage/api/v1/users/current/password', {
      old_password: oldPassword.value,
      new_password: newPassword.value
    })
    showPassword.value = false
    oldPassword.value = newPassword.value = passwordConfirm.value = ''
    signOut()
  } catch (reason) {
    passwordError.value = reason instanceof Error ? reason.message : '密码修改失败'
  } finally {
    passwordBusy.value = false
  }
}

onMounted(() => window.addEventListener('yoox:unauthorized', unauthorized))
onBeforeUnmount(() => window.removeEventListener('yoox:unauthorized', unauthorized))
</script>

<template>
  <RouterView v-if="isLogin" />
  <div v-else :class="['shell', { 'cockpit-shell': route.path === '/cockpit' }]">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">Y</div>
        <div><strong>YOOX</strong><small>Cloud GCS</small></div>
      </div>
      <nav>
        <RouterLink v-for="item in navigation" :key="item.to" :to="item.to">
          <span class="nav-icon">{{ item.icon }}</span>{{ item.label }}
        </RouterLink>
      </nav>
      <div class="side-foot">
        <span class="status-dot"></span>
        <div><strong>平台服务</strong><small>容器运行中</small></div>
      </div>
    </aside>
    <main class="main">
      <header class="topbar">
        <div>
          <p class="eyebrow">{{ session.workspace?.workspace_name || 'YOOX WORKSPACE' }}</p>
          <h1>{{ title }}</h1>
        </div>
        <div class="top-actions">
          <span class="protocol-badge">RTSP · P0</span>
          <button class="avatar" title="修改密码" @click="showPassword = true">{{ session.user?.username?.slice(0, 1).toUpperCase() }}</button>
          <button class="ghost small" @click="signOut">退出</button>
        </div>
      </header>
      <section class="content"><RouterView /></section>
    </main>
    <div v-if="showPassword" class="drawer-backdrop" @click.self="showPassword = false">
      <aside class="drawer password-drawer">
        <button class="drawer-close" @click="showPassword = false">×</button>
        <p class="eyebrow">ACCOUNT SECURITY</p><h2>修改登录密码</h2>
        <form class="task-form" @submit.prevent="changePassword">
          <label>当前密码<input v-model="oldPassword" type="password" autocomplete="current-password" required /></label>
          <label>新密码<input v-model="newPassword" type="password" minlength="12" maxlength="72" autocomplete="new-password" required /></label>
          <label>确认新密码<input v-model="passwordConfirm" type="password" minlength="12" maxlength="72" autocomplete="new-password" required /></label>
          <p class="help">至少 12 位，并同时包含大写字母、小写字母、数字和特殊字符。修改后需要重新登录。</p>
          <p v-if="passwordError" class="form-error">{{ passwordError }}</p>
          <button class="primary full" :disabled="passwordBusy">{{ passwordBusy ? '正在更新…' : '更新密码' }}</button>
        </form>
      </aside>
    </div>
  </div>
</template>

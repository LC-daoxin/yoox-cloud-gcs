import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import DashboardView from './views/DashboardView.vue'
import DevicesView from './views/DevicesView.vue'
import CockpitView from './views/CockpitView.vue'
import WaylinesView from './views/WaylinesView.vue'
import MediaView from './views/MediaView.vue'
import OperationsView from './views/OperationsView.vue'
import LoginView from './views/LoginView.vue'
import { useSessionStore } from './stores/session'
import './styles.css'

const routes = [
  { path: '/login', component: LoginView, meta: { public: true } },
  { path: '/', component: DashboardView, meta: { title: '运行总览' } },
  { path: '/cockpit', component: CockpitView, meta: { title: '虚拟座舱' } },
  { path: '/waylines', component: WaylinesView, meta: { title: '航线任务' } },
  { path: '/devices', component: DevicesView, meta: { title: '设备管理' } },
  { path: '/media', component: MediaView, meta: { title: '媒体中心' } },
  { path: '/operations', component: OperationsView, meta: { title: '系统运维' } },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({ history: createWebHistory(), routes })
const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)

router.beforeEach((to) => {
  const session = useSessionStore()
  if (!to.meta.public && !session.token) return '/login'
  if (to.path === '/login' && session.token) return '/'
})

app.mount('#app')

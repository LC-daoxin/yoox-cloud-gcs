import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { get, post, setToken } from '../services/api'
import type { SessionUser, Workspace } from '../types'

const USER_KEY = 'yoox_session_user'
const WORKSPACE_KEY = 'yoox_workspace'

function restore<T>(key: string): T | null {
  try {
    const value = localStorage.getItem(key)
    return value ? JSON.parse(value) as T : null
  } catch {
    return null
  }
}

export const useSessionStore = defineStore('session', () => {
  const user = ref<SessionUser | null>(restore<SessionUser>(USER_KEY))
  const workspace = ref<Workspace | null>(restore<Workspace>(WORKSPACE_KEY))
  const token = computed(() => user.value?.access_token ?? '')
  const workspaceId = computed(() => user.value?.workspace_id ?? workspace.value?.workspace_id ?? '')

  async function login(username: string, password: string) {
    const authenticated = await post<SessionUser>('/manage/api/v1/login', {
      username,
      password,
      flag: 1
    })
    user.value = authenticated
    setToken(authenticated.access_token)
    localStorage.setItem(USER_KEY, JSON.stringify(authenticated))
    await loadWorkspace()
  }

  async function loadWorkspace() {
    if (!token.value) return
    const current = await get<Workspace>('/manage/api/v1/workspaces/current')
    workspace.value = current
    localStorage.setItem(WORKSPACE_KEY, JSON.stringify(current))
  }

  async function refresh() {
    if (!token.value) return
    const refreshed = await post<SessionUser>('/manage/api/v1/token/refresh')
    user.value = refreshed
    setToken(refreshed.access_token)
    localStorage.setItem(USER_KEY, JSON.stringify(refreshed))
  }

  function logout() {
    user.value = null
    workspace.value = null
    setToken('')
    localStorage.removeItem(USER_KEY)
    localStorage.removeItem(WORKSPACE_KEY)
  }

  return { user, workspace, token, workspaceId, login, loadWorkspace, refresh, logout }
})

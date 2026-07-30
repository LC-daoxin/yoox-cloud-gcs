<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { get, getToken, listFrom } from '../services/api'
import { useSessionStore } from '../stores/session'

interface Media {
  file_id: string
  file_name: string
  sub_file_type?: string
  drone?: string
  payload?: string
  create_time?: string
  job_id?: string
}

const session = useSessionStore()
const media = ref<Media[]>([])
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  try {
    media.value = listFrom<Media>(await get(`/media/api/v1/files/${session.workspaceId}/files?page=1&page_size=60`))
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '媒体数据加载失败'
  } finally {
    loading.value = false
  }
}

async function openFile(file: Media) {
  const response = await fetch(`/media/api/v1/files/${session.workspaceId}/file/${file.file_id}/url`, {
    headers: { 'x-auth-token': getToken() },
    redirect: 'follow'
  })
  if (response.ok) window.open(response.url, '_blank', 'noopener')
  else error.value = '无法获取文件访问地址'
}

onMounted(load)
</script>

<template>
  <div class="stack">
    <div class="toolbar"><div><p class="eyebrow">MISSION ASSETS</p><h2>任务媒体归档</h2></div><button class="ghost" @click="load">刷新媒体</button></div>
    <div v-if="error" class="notice danger">{{ error }}</div>
    <div v-if="media.length" class="media-grid">
      <article v-for="file in media" :key="file.file_id" class="media-card" @click="openFile(file)">
        <div class="media-preview"><span>{{ file.sub_file_type?.toUpperCase() || 'MEDIA' }}</span><i>▧</i></div>
        <div><strong>{{ file.file_name }}</strong><small>{{ file.drone || '未知设备' }} · {{ file.create_time || '时间未上报' }}</small></div>
      </article>
    </div>
    <article v-else class="panel empty">{{ loading ? '正在同步 MinIO 媒体索引…' : '暂无媒体。执行带拍照或录像动作的航线任务后，文件会自动归档到这里。' }}</article>
  </div>
</template>

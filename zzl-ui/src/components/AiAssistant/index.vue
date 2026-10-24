<template>
  <el-drawer v-model="visible" title="AI 简聊" class="ai-drawer" size="520px" append-to-body>
    <div class="ai-panel">
      <div class="ai-toolbar">
        <div>
          <div class="ai-title">快速问答</div>
          <div class="ai-status">{{ statusText }} · 会话自动保存</div>
        </div>
        <el-button link size="small" @click="newConversation">新对话</el-button>
        <el-button link size="small" @click="openWorkbench">完整工作台</el-button>
      </div>

      <div class="ai-conversations" v-if="conversations.length">
        <div
          v-for="item in conversations"
          :key="item.id"
          :class="['ai-conversation', { active: item.id === conversationId }]"
          @click="selectConversation(item.id)"
        >
          <span class="conversation-title">{{ item.title || '新对话' }}</span>
          <el-button v-hasPermi="['ai:chat:remove']" link size="small" class="conversation-delete" @click.stop="deleteConversation(item.id)">×</el-button>
        </div>
      </div>

      <div class="ai-messages" ref="messagesRef">
        <div v-if="!messages.length" class="ai-empty">
          <div class="ai-orb">✦</div>
          <strong>问我系统现在的状态</strong>
          <span>我可以读取首页指标，并解释管理数据。</span>
        </div>
        <div v-for="(item, index) in messages" :key="index" :class="['ai-message', item.role]">
          <span v-if="item.role === 'assistant'" class="ai-label">助手</span>
          <div v-if="item.role === 'assistant'" class="markdown-body" v-html="renderMarkdown(item.content)" />
          <div v-else>{{ item.content }}</div>
        </div>
      </div>

      <div class="ai-prompts">
        <el-button v-for="prompt in prompts" :key="prompt" v-hasPermi="['ai:chat:send']" size="small" plain @click="ask(prompt)">{{ prompt }}</el-button>
      </div>
      <div class="ai-input">
        <el-input v-model="question" :disabled="!checkPermi(['ai:chat:send'])" type="textarea" :rows="3" resize="none" placeholder="输入问题，例如：现在有多少在线用户？" @keydown.enter.exact.prevent="ask()" />
        <div class="ai-actions">
          <span>Enter 发送</span>
          <el-button v-hasPermi="['ai:chat:send']" type="primary" :loading="loading" @click="ask()">发送</el-button>
        </div>
      </div>
    </div>
  </el-drawer>

</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { getToken } from '@/utils/auth'
import { checkPermi } from '@/utils/permission'
import { useRouter } from 'vue-router'

const visible = defineModel({ type: Boolean, default: false })
const question = ref('')
const loading = ref(false)
const connected = ref(false)
const toolStatus = ref('')
const models = ref([])
const conversations = ref([])
const selectedModel = ref('')
const conversationId = ref(localStorage.getItem('zzl-ai-conversation-id') || newConversationId())
const messages = ref(readMessages(conversationId.value))
const messagesRef = ref(null)
const prompts = ['现在有多少在线用户？', '查看首页指标', '系统状态怎么样？']
const router = useRouter()

const statusText = computed(() => {
  if (loading.value && toolStatus.value) return toolStatus.value
  if (connected.value) return '已连接'
  return '待提问'
})

onMounted(() => {
  loadModels()
  loadConversations()
  nextTick(scrollToBottom)
})

watch(messages, saveMessages, { deep: true })
watch(conversationId, value => localStorage.setItem('zzl-ai-conversation-id', value))

function openWorkbench() {
  visible.value = false
  router.push('/ai/workbench')
}

function newConversationId() {
  return `conversation-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function apiHeaders() {
  return { Authorization: `Bearer ${getToken()}` }
}

async function loadModels() {
  const baseUrl = import.meta.env.VITE_APP_BASE_API || ''
  const previousModel = selectedModel.value
  try {
    const response = await fetch(`${baseUrl}/system/ai/models`, { headers: apiHeaders() })
    if (!response.ok) throw new Error('AI 模型列表读取失败')
    const payload = await response.json()
    models.value = Array.isArray(payload.data) ? payload.data : []
    const defaultModel = models.value.find(item => item.defaultModel) || models.value.find(item => item.configured)
    selectedModel.value = models.value.some(item => item.id === previousModel)
      ? previousModel
      : defaultModel?.id || models.value[0]?.id || ''
  } catch (error) {
    models.value = [{ id: '', label: '本地摘要模式', configured: true }]
    selectedModel.value = ''
  }
}

async function loadConversations() {
  const baseUrl = import.meta.env.VITE_APP_BASE_API || ''
  try {
    const response = await fetch(`${baseUrl}/system/ai/conversations`, { headers: apiHeaders() })
    if (!response.ok) throw new Error('会话列表读取失败')
    const payload = await response.json()
    conversations.value = Array.isArray(payload.data) ? payload.data : []
    if (!conversations.value.some(item => item.id === conversationId.value)) {
      conversations.value.unshift({ id: conversationId.value, title: messages.value[0]?.content || '新对话', model: selectedModel.value })
    }
  } catch (error) {
    conversations.value = [{ id: conversationId.value, title: messages.value[0]?.content || '新对话', model: selectedModel.value }]
  }
}

async function selectConversation(id) {
  if (!id || id === conversationId.value || loading.value) return
  conversationId.value = id
  const baseUrl = import.meta.env.VITE_APP_BASE_API || ''
  try {
    const response = await fetch(`${baseUrl}/system/ai/conversations/${encodeURIComponent(id)}`, { headers: apiHeaders() })
    const payload = await response.json()
    messages.value = Array.isArray(payload.data) ? payload.data.map(item => ({ role: item.role, content: item.content })) : readMessages(id)
  } catch (error) {
    messages.value = readMessages(id)
  }
  await nextTick(scrollToBottom)
}

function newConversation() {
  if (loading.value) return
  conversationId.value = newConversationId()
  messages.value = []
  conversations.value.unshift({ id: conversationId.value, title: '新对话', model: selectedModel.value })
  connected.value = false
  toolStatus.value = ''
}

async function deleteConversation(id) {
  if (loading.value) return
  const baseUrl = import.meta.env.VITE_APP_BASE_API || ''
  try {
    await fetch(`${baseUrl}/system/ai/conversations/${encodeURIComponent(id)}`, { method: 'DELETE', headers: apiHeaders() })
  } catch (error) {
    // 本地缓存仍然会清除。
  }
  localStorage.removeItem(`zzl-ai-messages:${id}`)
  conversations.value = conversations.value.filter(item => item.id !== id)
  if (id === conversationId.value) newConversation()
}

function readMessages(id) {
  try {
    const saved = JSON.parse(localStorage.getItem(`zzl-ai-messages:${id}`) || '[]')
    return Array.isArray(saved) ? saved : []
  } catch (error) {
    return []
  }
}

function saveMessages() {
  localStorage.setItem(`zzl-ai-messages:${conversationId.value}`, JSON.stringify(messages.value.slice(-40)))
}

async function ask(value = question.value) {
  if (!checkPermi(['ai:chat:send'])) return
  const text = value.trim()
  if (!text || loading.value) return
  question.value = ''
  messages.value.push({ role: 'user', content: text })
  const answer = { role: 'assistant', content: '' }
  messages.value.push(answer)
  loading.value = true
  connected.value = false
  toolStatus.value = ''
  const current = conversations.value.find(item => item.id === conversationId.value)
  if (current && (!current.title || current.title === '新对话')) current.title = text.slice(0, 42)
  await nextTick()
  scrollToBottom()

  const baseUrl = import.meta.env.VITE_APP_BASE_API || ''
  const params = new URLSearchParams({ question: text, conversationId: conversationId.value })
  if (selectedModel.value) params.set('model', selectedModel.value)
  try {
    const response = await fetch(`${baseUrl}/system/ai/chat?${params.toString()}`, {
      headers: { ...apiHeaders(), Accept: 'text/event-stream' }
    })
    if (!response.ok || !response.body) throw new Error('AI 接口连接失败')
    connected.value = true
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { value: chunk, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(chunk, { stream: true })
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() || ''
      frames.forEach(frame => {
        let event = 'message'
        const data = []
        frame.split(/\r?\n/).forEach(line => {
          if (line.startsWith('event:')) event = line.slice(6).trim()
          if (line.startsWith('data:')) data.push(line.slice(5).trim())
        })
        const payload = data.join('\n')
        if (event === 'message') answer.content += payload
        if (event === 'status') toolStatus.value = payload
        if (event === 'run') toolStatus.value = 'Agent 已启动'
        if (event === 'tool') {
          try {
            const tool = JSON.parse(payload)
            toolStatus.value = tool.detail || `正在调用 ${tool.tool || '管理工具'}`
          } catch (error) {
            toolStatus.value = payload
          }
        }
        if (event === 'error') answer.content += `\n\n${payload}`
      })
      await nextTick()
      scrollToBottom()
    }
  } catch (error) {
    if (!answer.content) answer.content = 'AI 助手暂时无法连接，请确认后端服务已重启。'
  } finally {
    loading.value = false
    toolStatus.value = ''
  }
}

function escapeHtml(value) {
  return String(value || '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]))
}

function renderMarkdown(value) {
  let html = escapeHtml(value)
  html = html.replace(/```([\s\S]*?)```/g, '<pre><code>$1</code></pre>')
  html = html.replace(/^### (.*)$/gm, '<h4>$1</h4>')
  html = html.replace(/^## (.*)$/gm, '<h3>$1</h3>')
  html = html.replace(/^# (.*)$/gm, '<h2>$1</h2>')
  html = html.replace(/^[-*] (.*)$/gm, '<li>$1</li>')
  html = html.replace(/(<li>[\s\S]*?<\/li>)/g, '<ul>$1</ul>')
  html = html.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/`([^`]+)`/g, '<code>$1</code>')
  return html.replace(/\n/g, '<br>')
}

function scrollToBottom() {
  if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight
}
</script>

<style scoped lang="scss">
:global(.ai-drawer) { --el-drawer-bg-color: #fffdf9; --el-drawer-padding-primary: 20px; }
:global(.ai-drawer .el-drawer__header) { margin-bottom: 0; padding-bottom: 16px; border-bottom: 1px solid #e9e3ee; color: #2b3044; }
:global(.ai-drawer .el-drawer__title) { color: #2b3044; font-family: "Avenir Next", "Noto Sans SC", sans-serif; font-size: 16px; font-weight: 650; }
:global(.ai-drawer .el-drawer__close-btn) { color: #7d7e91; }
:global(.ai-drawer .el-drawer__close-btn:hover) { color: #45b99e; }
.ai-panel { --ink: #fff9f2; --surface: #fffdf9; --surface-raised: #ffffff; --line: #e9e3ee; --text: #2b3044; --muted: #7d7e91; --teal: #45b99e; display: flex; flex-direction: column; height: 100%; gap: 12px; color: var(--text); background: var(--surface); font-family: "Avenir Next", "Noto Sans SC", "PingFang SC", "Segoe UI", sans-serif; }
.ai-toolbar { display: flex; align-items: center; gap: 8px; }
.ai-title { color: var(--text); font-size: 15px; font-weight: 650; }
.ai-status { color: var(--muted); font-size: 11px; }
.ai-model { width: 145px; margin-left: auto; }
.ai-conversations { display: flex; gap: 6px; overflow-x: auto; padding-bottom: 2px; }
.ai-conversation { display: flex; align-items: center; max-width: 180px; flex: 0 0 auto; padding: 6px 8px 6px 10px; border: 1px solid var(--line); border-radius: 7px; color: var(--muted); cursor: pointer; font-size: 11px; background: rgba(19, 52, 64, .42); }
.ai-conversation.active { border-color: rgba(130, 227, 213, .55); color: var(--teal); background: rgba(130, 227, 213, .08); }
.conversation-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conversation-delete { margin-left: 4px; color: #6f8f95 !important; }
.ai-messages { flex: 1; overflow-y: auto; padding: 8px 2px; scrollbar-color: #315762 transparent; }
.ai-empty { position: relative; display: grid; place-items: center; gap: 8px; min-height: 260px; color: var(--muted); text-align: center; }
.ai-empty strong { color: var(--text); font-weight: 600; }
.ai-empty span { font-size: 12px; }
.ai-orb { position: relative; display: grid; place-items: center; width: 54px; height: 54px; border: 1px solid rgba(69, 185, 158, .58); border-radius: 15px; color: var(--teal); font-size: 24px; background: #e8faf2; box-shadow: 0 0 0 8px rgba(69, 185, 158, .08); }
.ai-orb::before { position: absolute; width: 76px; height: 76px; border: 1px solid rgba(130, 227, 213, .18); border-radius: 50%; content: ''; }
.ai-message { max-width: 90%; padding: 10px 12px; margin: 0 0 12px; border: 1px solid var(--line); border-radius: 9px; line-height: 1.65; white-space: pre-wrap; }
.ai-message.user { margin-left: auto; border-color: rgba(233, 157, 86, .28); color: #67452b; background: #fff0dc; }
.ai-message.assistant { margin-right: auto; color: #4c5063; background: #ffffff; }
.ai-label { display: block; margin-bottom: 4px; color: var(--teal); font-size: 10px; font-weight: 700; }
.markdown-body :deep(h2), .markdown-body :deep(h3), .markdown-body :deep(h4) { margin: 4px 0 6px; color: var(--teal); }
.markdown-body :deep(ul) { padding-left: 18px; margin: 4px 0; }
.markdown-body :deep(pre) { padding: 8px; overflow-x: auto; border: 1px solid var(--line); border-radius: 6px; background: #2b3044; color: #f8fbfa; white-space: pre-wrap; }
.markdown-body :deep(code) { padding: 1px 4px; border-radius: 4px; color: #f8d28f; background: rgba(246, 189, 107, .12); }
.markdown-body :deep(pre code) { padding: 0; background: transparent; }
.ai-prompts { display: flex; flex-wrap: wrap; gap: 6px; }
.ai-input { padding-top: 8px; border-top: 1px solid var(--line); }
.ai-actions { display: flex; align-items: center; justify-content: space-between; margin-top: 8px; color: #6f8f95; font-size: 11px; }
.model-manager { max-height: 65vh; overflow-y: auto; }
.model-list { display: grid; gap: 8px; }
.model-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 9px 10px; border: 1px solid var(--line); border-radius: 7px; }
.model-row-main { display: grid; gap: 3px; min-width: 0; }
.model-row-main strong { overflow: hidden; color: var(--text); text-overflow: ellipsis; white-space: nowrap; }
.model-row-main span { overflow: hidden; color: var(--muted); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.model-row-actions { display: flex; align-items: center; flex: 0 0 auto; }
.model-form-title { margin-bottom: 12px; color: var(--text); font-weight: 650; }
.model-form-actions { display: flex; justify-content: flex-end; gap: 8px; }
.ai-panel :deep(.el-textarea__inner) { border: 1px solid var(--line); border-radius: 8px; color: var(--text); background: #ffffff; box-shadow: none; }
.ai-panel :deep(.el-textarea__inner::placeholder) { color: #66858b; }
.ai-panel :deep(.el-button) { --el-button-text-color: var(--muted); --el-button-hover-text-color: var(--teal); --el-button-hover-bg-color: rgba(130, 227, 213, .08); }
.ai-panel :deep(.el-button--primary) { --el-button-bg-color: var(--teal); --el-button-border-color: var(--teal); --el-button-hover-bg-color: #a1f0e5; --el-button-hover-border-color: #a1f0e5; --el-button-text-color: var(--ink); }
.ai-panel :deep(.el-button.is-plain) { border-color: var(--line); }
</style>

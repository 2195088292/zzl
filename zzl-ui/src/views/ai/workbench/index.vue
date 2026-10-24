<template>
  <div class="ai-workbench">
    <header class="workbench-header">
      <div class="header-copy">
        <p class="eyebrow">Agent 工作区</p>
        <h1>AI 工作台</h1>
        <p>把日常问答、系统数据和文件分析放在一个连续的工作区里。</p>
      </div>
      <div class="header-actions">
        <el-tag :type="loading ? 'warning' : runState === 'error' ? 'danger' : connected ? 'success' : 'info'" effect="plain">
          {{ loading ? runStatusLabel : runState === 'error' ? '执行失败' : connected ? '模型已连接' : '等待提问' }}
        </el-tag>
        <el-select v-model="selectedModel" class="model-select" placeholder="选择模型" :disabled="loading">
          <el-option v-for="model in modelChoices" :key="model.value" :value="model.value" :label="model.label" />
        </el-select>
        <el-select v-model="selectedKnowledgeBaseIds" class="knowledge-select" multiple collapse-tags collapse-tags-tooltip placeholder="选择知识库" :disabled="loading">
          <el-option v-for="base in knowledgeBases" :key="base.id" :value="base.id" :label="base.name" />
        </el-select>
        <el-select v-model="selectedReasoning" class="reasoning-select" placeholder="推理强度" :disabled="loading || reasoningDisabled">
          <el-option v-for="option in reasoningChoices" :key="option.value" :value="option.value" :label="option.label" />
        </el-select>
        <small v-if="reasoningDisabled" class="model-lock-note">当前模型使用原生推理策略</small>
        <el-button v-if="loading" v-hasPermi="['ai:chat:send']" type="danger" plain @click="stopRun">停止</el-button>
        <el-button plain @click="newConversation">新对话</el-button>
      </div>
    </header>

    <section class="workspace-grid">
      <aside class="session-sidebar panel">
        <div class="sidebar-heading">
          <strong>对话</strong>
          <el-button text type="primary" @click="newConversation">＋</el-button>
        </div>
        <el-input v-model="conversationFilter" clearable placeholder="搜索对话" class="conversation-search" />
        <div class="conversation-list">
          <button
            v-for="item in filteredConversations"
            :key="item.id"
            type="button"
            :class="['conversation-item', { active: item.id === conversationId }]"
            @click="selectConversation(item.id)"
          >
            <span class="conversation-icon">✦</span>
            <span class="conversation-meta">
              <strong>{{ item.title || '新对话' }}</strong>
            <small>{{ modelDisplayLabel(item.model) }}</small>
            </span>
            <el-button v-hasPermi="['ai:chat:remove']" text class="delete-button" @click.stop="deleteConversation(item.id)">×</el-button>
          </button>
          <div v-if="!filteredConversations.length" class="empty-sidebar">还没有匹配的对话</div>
        </div>
        <div class="sidebar-footer">会话自动保存到当前账号</div>
      </aside>

      <main class="chat-panel panel">
        <div class="chat-heading">
          <div>
            <strong>{{ activeConversation?.title || '新对话' }}</strong>
            <span>{{ selectedModelLabel }} · {{ selectedReasoningLabel }}</span>
          </div>
          <el-button v-hasPermi="['ai:chat:remove']" text @click="clearMessages" :disabled="loading || !messages.length">清空当前对话</el-button>
        </div>

        <div ref="messagesRef" class="message-list">
          <div v-if="!messages.length" class="welcome-state">
            <div class="welcome-orb">✦</div>
            <h2>你好，我是你的 AI 工作助手</h2>
            <p>可以询问首页指标、解释系统状态，也可以上传文本、表格或代码让我先读一遍。</p>
            <div class="suggestions">
              <button v-for="prompt in prompts" :key="prompt" v-hasPermi="['ai:chat:send']" type="button" @click="ask(prompt)">{{ prompt }}</button>
            </div>
          </div>
          <div v-for="(item, index) in messages" :key="`${conversationId}-${index}`" :class="['message-row', item.role]">
            <div class="message-avatar">{{ item.role === 'user' ? '我' : 'AI' }}</div>
            <div class="message-bubble">
              <div v-if="item.attachments?.length" class="message-files">
                <span v-for="file in item.attachments" :key="file.name" class="file-chip">📎 {{ file.name }}</span>
              </div>
              <div v-if="item.role === 'assistant'" class="markdown-body" v-html="renderMarkdown(item.content)" />
              <div v-else class="message-content">{{ item.content }}</div>
              <small v-if="item.role === 'assistant' && item.model" class="message-model-meta">{{ modelDisplayLabel(item.model) }} · {{ providerDisplayLabel(item.providerType) }} · {{ reasoningDisplayLabel(item.reasoningEffort) }}</small>
              <div v-if="item.citations?.length" class="message-citations">
                <div class="citation-heading">引用来源</div>
                <div v-for="(citation, citationIndex) in item.citations" :key="citation.key || `${citation.name}-${citationIndex}`" class="citation-card" role="button" tabindex="0" @click="openCitation(citation)" @keydown.enter="openCitation(citation)">
                  <span class="citation-marker">{{ citationIndex + 1 }}</span>
                  <div class="citation-content">
                    <strong>📄 {{ citation.name }}</strong>
                    <small v-if="citation.pageLabel">{{ citation.pageLabel }}</small>
                    <p v-if="citation.snippet">{{ citation.snippet }}</p>
                  </div>
                </div>
              </div>
            </div>
          </div>
          <div v-if="loading && toolStatus" class="tool-status">{{ toolStatus }}</div>
        </div>

        <div v-if="attachments.length" class="attachment-strip">
          <div v-for="file in attachments" :key="file.id" class="attachment-item">
            <span>📎 {{ file.name }}</span>
            <small>{{ formatSize(file.size) }}</small>
            <span :title="indexStatusMessage(file)" :class="['index-status', `index-status-${indexStatus(file)}`]">
              {{ indexStatusLabel(file) }}
            </span>
            <button type="button" @click="removeAttachment(file.id)">×</button>
          </div>
        </div>
        <div class="composer">
          <input ref="fileInput" type="file" multiple accept="image/*,.txt,.md,.csv,.tsv,.json,.xml,.html,.htm,.java,.js,.ts,.jsx,.tsx,.vue,.css,.scss,.less,.sql,.yaml,.yml,.properties,.log,.py,.go,.rs,.sh,.bat,.ps1,.conf,.ini,.env,.docx,.xlsx,.xls,.pptx,.pdf" hidden @change="onFileChange" />
          <el-button v-hasPermi="['ai:file:upload']" circle plain title="上传文件" @click="fileInput?.click()">📎</el-button>
          <el-input
            v-model="question"
            :disabled="!checkPermi(['ai:chat:send'])"
            type="textarea"
            :rows="3"
            resize="none"
            placeholder="输入问题，或上传文件后让 AI 帮你分析"
            @keydown.enter.exact.prevent="ask()"
          />
          <el-button v-hasPermi="['ai:chat:send']" type="primary" class="send-button" :loading="loading" :disabled="hasPendingUploads" @click="ask()">发送</el-button>
        </div>
        <div class="composer-tip">{{ hasPendingUploads ? '文件正在上传和建立索引，请稍候' : 'Enter 发送 · 支持图片、txt、md、csv、json、代码、PDF、DOCX、Excel · 单文件不超过 5 MB' }}</div>
      </main>

      <aside class="capability-sidebar">
        <div class="panel capability-panel">
          <div class="sidebar-heading"><strong>工作能力</strong><span class="live-dot"></span></div>
          <div v-for="item in capabilities" :key="item.title" class="capability-item">
            <span class="capability-icon">{{ item.icon }}</span>
            <div><strong>{{ item.title }}</strong><small>{{ item.desc }}</small></div>
          </div>
        </div>
        <div class="panel shortcut-panel">
          <div class="sidebar-heading"><strong>快捷提问</strong></div>
          <button v-for="prompt in prompts" :key="`shortcut-${prompt}`" v-hasPermi="['ai:chat:send']" type="button" @click="ask(prompt)">{{ prompt }}</button>
        </div>
        <div class="panel notice-panel">
          <strong>模型与附件</strong>
          <p>模型配置已移到左侧“模型管理”。当前页面只负责工作流和对话。</p>
          <span>{{ attachments.length ? `已选择 ${attachments.length} 个附件` : '尚未添加附件' }}</span>
        </div>
        <div class="panel run-panel">
          <div class="sidebar-heading">
            <strong>Agent 执行</strong>
            <div class="run-heading-tags">
              <el-tag v-if="runRagState !== 'idle' || runCitations.length" size="small" effect="plain">
                {{ retrievalModeLabel }}
              </el-tag>
              <el-tag size="small" :type="runState === 'error' ? 'danger' : loading ? 'warning' : 'info'" effect="plain">
                {{ runStatusLabel }}
              </el-tag>
            </div>
          </div>
          <div v-if="runStartedAt" class="run-meta">{{ runDurationLabel }} · {{ runSteps.length }} 个步骤</div>
          <div v-if="runRagState !== 'idle' || runCitations.length" class="retrieval-status">
            <span class="retrieval-status-label">文件检索</span>
            <strong>{{ retrievalStateLabel }}</strong>
            <small>{{ retrievalModeDescription }}</small>
            <small v-if="runRetrievalTrace">{{ runRetrievalTrace }}</small>
          </div>
          <div v-if="runSteps.length" class="run-timeline">
            <div v-for="(step, index) in runSteps" :key="`${step.at}-${index}`" :class="['run-step', step.state]">
              <span class="run-step-dot"></span>
              <div class="run-step-content">
                <strong>{{ step.title }}</strong>
                <small>{{ step.detail }}</small>
              </div>
            </div>
          </div>
          <div v-else class="run-empty">发送问题后，这里会显示思考、工具调用和结果。</div>
          <div v-if="runFiles.length" class="run-files">
            <span class="run-files-label">本次引用</span>
            <span v-for="file in runFiles" :key="file.fileId || file.name" class="run-file-chip">📎 {{ file.name }}</span>
          </div>
          <div v-if="runCitations.length" class="run-citations">
            <span class="run-files-label">文件引用</span>
            <div v-for="(citation, citationIndex) in runCitations" :key="citation.key || `${citation.name}-${citationIndex}`" class="run-citation-card" role="button" tabindex="0" @click="openCitation(citation)" @keydown.enter="openCitation(citation)">
              <strong>📄 {{ citation.name }}</strong>
              <small v-if="citation.pageLabel">{{ citation.pageLabel }}</small>
              <p v-if="citation.snippet">{{ citation.snippet }}</p>
            </div>
          </div>
        </div>
      </aside>
    </section>
    <div v-if="citationPreview.open" class="citation-preview-overlay" @click.self="closeCitation">
      <section class="citation-preview panel">
        <div class="citation-preview-heading">
          <div>
            <strong>{{ citationPreview.name || '文件引用' }}</strong>
            <small>{{ citationPreview.location }}</small>
          </div>
          <el-button text @click="closeCitation">关闭</el-button>
        </div>
        <div v-if="citationPreview.loading" class="citation-preview-state">正在读取原文…</div>
        <div v-else-if="citationPreview.error" class="citation-preview-state error">{{ citationPreview.error }}</div>
        <pre v-else class="citation-preview-content">{{ citationPreview.content }}</pre>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getToken } from '@/utils/auth'
import { checkPermi } from '@/utils/permission'

const question = ref('')
const loading = ref(false)
const connected = ref(false)
const toolStatus = ref('')
const runState = ref('idle')
const runSteps = ref([])
const runFiles = ref([])
const runCitations = ref([])
const runRagState = ref('idle')
const runRetrievalMode = ref('none')
const runRetrievalTrace = ref('')
const ragStepAdded = ref(false)
const runStartedAt = ref(0)
const runFinishedAt = ref(0)
const runId = ref('')
const abortController = ref(null)
const models = ref([])
const selectedModel = ref('')
const selectedReasoning = ref('auto')
const knowledgeBases = ref([])
const selectedKnowledgeBaseIds = ref([])
const conversations = ref([])
const conversationFilter = ref('')
const conversationId = ref(localStorage.getItem('zzl-ai-workbench-conversation') || newConversationId())
const messages = ref(readMessages(conversationId.value))
const messagesRef = ref(null)
const fileInput = ref(null)
const attachments = ref([])
const citationPreview = reactive({ open: false, loading: false, name: '', location: '', content: '', error: '' })
const hasPendingUploads = computed(() => attachments.value.some(file => file.uploadPending || indexStatus(file) === 'indexing'))
const indexPollers = new Map()
const prompts = ['查看首页指标', '系统状态怎么样？', '帮我解释最近的操作日志']
const capabilities = [
  { icon: '⌁', title: '系统数据问答', desc: '读取首页指标与服务状态' },
  { icon: '▧', title: '文件分析', desc: '提取文本、表格和代码内容' },
  { icon: '⌘', title: '连续对话', desc: '保留会话上下文与历史记录' },
  { icon: '✎', title: '内容整理', desc: '总结、改写和生成工作草稿' }
]

const activeConversation = computed(() => conversations.value.find(item => item.id === conversationId.value))
const filteredConversations = computed(() => {
  const value = conversationFilter.value.trim().toLowerCase()
  return value ? conversations.value.filter(item => (item.title || '').toLowerCase().includes(value)) : conversations.value
})
const modelChoices = computed(() => models.value.flatMap(model => {
  const rawCatalog = Array.isArray(model.modelCatalog) && model.modelCatalog.length
    ? model.modelCatalog
    : (model.modelName ? [model.modelName] : [''])
  const catalog = rawCatalog.map(item => typeof item === 'string'
    ? { id: item, label: item, reasoningLevels: null }
    : { id: item?.id || '', label: item?.label || item?.id || '', reasoningLevels: item?.reasoningLevels || null })
  const defaultReasoningLevels = model.reasoningLevels || model.capabilities?.reasoningLevels || ['auto', 'low', 'medium', 'high']
  return catalog.map(option => {
    const modelName = option.id
    return {
      value: modelName ? `${model.id}::${modelName}` : model.id,
      baseId: model.id,
      modelName,
      providerType: model.providerType || model.capabilities?.providerType || 'custom',
      capabilityProtocol: model.capabilityProtocol || model.capabilities?.capabilityProtocol || 'generic_reasoning_effort',
      reasoningLevels: option.reasoningLevels || defaultReasoningLevels,
      label: `${model.label || model.id || '本地摘要模式'}${modelName ? ` · ${option.label || modelName}` : ''}${model.configured ? '' : ' · 未配置'}`
    }
  })
}))
const selectedModelLabel = computed(() => modelChoices.value.find(item => item.value === selectedModel.value)?.label || '本地摘要模式')
const selectedModelConfig = computed(() => {
  const choice = modelChoices.value.find(item => item.value === selectedModel.value)
  return models.value.find(item => item.id === choice?.baseId) || null
})
const selectedModelChoice = computed(() => modelChoices.value.find(item => item.value === selectedModel.value) || null)
const reasoningChoices = computed(() => {
  const levels = selectedModelChoice.value?.reasoningLevels
    || selectedModelConfig.value?.reasoningLevels
    || selectedModelConfig.value?.capabilities?.reasoningLevels
    || ['auto', 'low', 'medium', 'high']
  const normalized = [...new Set(['auto', ...levels.map(item => String(item).toLowerCase())])]
  return normalized.map(value => ({ value, label: value === 'auto' ? '自动（模型默认）' : value === 'low' ? '低' : value === 'medium' ? '中' : value === 'high' ? '高' : value }))
})
const reasoningDisabled = computed(() => reasoningChoices.value.length <= 1)
const selectedReasoningLabel = computed(() => reasoningChoices.value.find(item => item.value === selectedReasoning.value)?.label || '自动')
const runStatusLabel = computed(() => ({
  idle: '待执行',
  running: '执行中',
  done: '已完成',
  cancelled: '已停止',
  error: '失败'
}[runState.value] || '待执行'))
const runDurationLabel = computed(() => {
  if (!runStartedAt.value) return ''
  const end = runFinishedAt.value || Date.now()
  return `${Math.max(0, ((end - runStartedAt.value) / 1000).toFixed(1))} 秒`
})
const retrievalModeLabel = computed(() => ({
  none: '未检索文件',
  keyword: '关键词检索',
  hybrid: '混合检索',
  'qdrant-hybrid': '混合检索',
  vector: '向量检索'
}[runRetrievalMode.value] || '文件检索'))
const retrievalModeDescription = computed(() => ({
  none: '本次对话未使用文件检索',
  keyword: '按问题关键词匹配文件片段',
  hybrid: '关键词与向量召回共同排序',
  'qdrant-hybrid': '关键词与向量召回通过 RRF 融合排序',
  vector: '按语义向量召回相关片段'
}[runRetrievalMode.value] || '已选择文件，等待检索结果'))
const retrievalStateLabel = computed(() => ({
  pending: '准备中',
  searching: '检索中',
  ready: `已命中 ${runCitations.value.length} 个片段`,
  empty: '未命中相关片段',
  error: '检索失败'
}[runRagState.value] || '未开始'))

onMounted(async () => {
  await Promise.all([loadModels(), loadKnowledgeBases()])
  await loadConversations()
  nextTick(scrollToBottom)
})

onBeforeUnmount(() => {
  stopAllIndexPolling()
})

watch(messages, saveMessages, { deep: true })
watch(conversationId, value => {
  localStorage.setItem('zzl-ai-workbench-conversation', value)
  const current = conversations.value.find(item => item.id === value)
  if (current?.model) localStorage.setItem(modelStorageKey(value), current.model)
})
watch(selectedModel, value => {
  if (!value) return
  localStorage.setItem(modelStorageKey(conversationId.value), value)
  const current = conversations.value.find(item => item.id === conversationId.value)
  if (current) current.model = value
  if (!reasoningChoices.value.some(item => item.value === selectedReasoning.value)) {
    selectedReasoning.value = 'auto'
  }
})
watch(selectedReasoning, value => {
  if (!value) return
  localStorage.setItem(reasoningStorageKey(conversationId.value), value)
})

watch(selectedKnowledgeBaseIds, value => {
  localStorage.setItem('zzl-ai-knowledge-bases', JSON.stringify(value || []))
}, { deep: true })

function newConversationId() {
  return `workbench-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function apiHeaders() {
  const token = getToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function loadModels() {
  try {
    const response = await fetch(`${import.meta.env.VITE_APP_BASE_API || ''}/system/ai/models`, { headers: apiHeaders() })
    const payload = await response.json()
    models.value = Array.isArray(payload.data) ? payload.data : []
    const saved = localStorage.getItem(modelStorageKey(conversationId.value))
    const configured = models.value.find(item => item.defaultModel) || models.value.find(item => item.configured)
    const preferred = resolveModelChoice(saved)
      || (configured && modelChoices.value.find(item => item.baseId === configured.id))
      || modelChoices.value[0]
    selectedModel.value = preferred?.value || ''
    restoreConversationReasoning()
  } catch (error) {
    models.value = [{ id: '', label: '本地摘要模式', configured: true }]
  }
}

async function loadKnowledgeBases() {
  try {
    const response = await fetch(`${import.meta.env.VITE_APP_BASE_API || ''}/system/ai/knowledge-bases`, { headers: apiHeaders() })
    const payload = await response.json()
    knowledgeBases.value = Array.isArray(payload.data) ? payload.data.filter(item => item.enabled) : []
    const saved = JSON.parse(localStorage.getItem('zzl-ai-knowledge-bases') || '[]')
    selectedKnowledgeBaseIds.value = Array.isArray(saved)
      ? saved.filter(id => knowledgeBases.value.some(item => item.id === id))
      : []
  } catch (error) {
    knowledgeBases.value = []
    selectedKnowledgeBaseIds.value = []
  }
}

async function loadConversations() {
  try {
    const response = await fetch(`${import.meta.env.VITE_APP_BASE_API || ''}/system/ai/conversations`, { headers: apiHeaders() })
    const payload = await response.json()
    conversations.value = Array.isArray(payload.data) ? payload.data : []
  } catch (error) {
    conversations.value = []
  }
  if (!conversations.value.some(item => item.id === conversationId.value)) {
    conversations.value.unshift({ id: conversationId.value, title: messages.value[0]?.content?.slice(0, 42) || '新对话', model: selectedModel.value })
  }
  await loadCurrentConversationMessages()
  restoreConversationModel()
}

async function loadCurrentConversationMessages() {
  try {
    const response = await fetch(`${import.meta.env.VITE_APP_BASE_API || ''}/system/ai/conversations/${encodeURIComponent(conversationId.value)}`, { headers: apiHeaders() })
    const payload = await response.json()
    const serverMessages = Array.isArray(payload.data) ? payload.data.map(mapConversationMessage) : []
    // 后端是长期记录；只有后端没有记录时才保留本地草稿缓存。
    if (serverMessages.length) messages.value = serverMessages
  } catch (error) {
    // 本地缓存已经在 setup 阶段加载，接口失败时继续使用它。
  }
}

async function selectConversation(id) {
  if (!id || id === conversationId.value || loading.value) return
  conversationId.value = id
  try {
    const response = await fetch(`${import.meta.env.VITE_APP_BASE_API || ''}/system/ai/conversations/${encodeURIComponent(id)}`, { headers: apiHeaders() })
    const payload = await response.json()
    messages.value = Array.isArray(payload.data)
      ? payload.data.map(mapConversationMessage)
      : readMessages(id)
  } catch (error) {
    messages.value = readMessages(id)
  }
  attachments.value = []
  resetRun()
  restoreConversationModel()
  await nextTick(scrollToBottom)
}

function mapConversationMessage(item) {
  return {
    role: item.role,
    content: item.content,
    model: item.model || '',
    reasoningEffort: item.reasoningEffort || 'auto',
    providerType: item.providerType || '',
    capabilityProtocol: item.capabilityProtocol || '',
    attachments: Array.isArray(item.attachments) ? item.attachments : [],
    citations: Array.isArray(item.citations) ? item.citations : []
  }
}

function newConversation() {
  if (loading.value) return
  conversationId.value = newConversationId()
  messages.value = []
  attachments.value = []
  conversations.value.unshift({ id: conversationId.value, title: '新对话', model: selectedModel.value })
  connected.value = false
  toolStatus.value = ''
  resetRun()
}

async function deleteConversation(id) {
  if (loading.value) return
  try {
    await fetch(`${import.meta.env.VITE_APP_BASE_API || ''}/system/ai/conversations/${encodeURIComponent(id)}`, { method: 'DELETE', headers: apiHeaders() })
  } catch (error) { /* local state still clears */ }
  localStorage.removeItem(`zzl-ai-workbench:${id}`)
  conversations.value = conversations.value.filter(item => item.id !== id)
  if (id === conversationId.value) newConversation()
}

function clearMessages() {
  messages.value = []
  localStorage.removeItem(`zzl-ai-workbench:${conversationId.value}`)
  resetRun()
}

async function onFileChange(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  for (const file of files) {
    if (file.size > 5 * 1024 * 1024) {
      ElMessage.warning(`文件 ${file.name} 超过 5 MB，已跳过`)
      continue
    }
    const pendingId = `upload-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    const pendingFile = {
      id: pendingId,
      fileId: '',
      name: file.name,
      size: file.size,
      extracted: false,
      indexStatus: 'indexing',
      uploadPending: true
    }
    attachments.value.push(pendingFile)
    const form = new FormData()
    form.append('file', file)
    try {
      const response = await fetch(`${import.meta.env.VITE_APP_BASE_API || ''}/system/ai/workspace/files?conversationId=${encodeURIComponent(conversationId.value)}`, { method: 'POST', headers: apiHeaders(), body: form })
      const payload = await response.json()
      if (!response.ok || payload.code !== 200 || !payload.data?.id) throw new Error(payload.msg || '文件上传失败')
      const info = payload.data
      const uploaded = {
        id: info.id,
        fileId: info.id,
        name: info.originalName || file.name,
        contentType: info.contentType || file.type || '',
        size: info.size || file.size,
        extracted: info.textExtracted,
        extractMessage: info.extractMessage,
        indexedChunks: info.indexedChunks ?? info.chunkCount ?? info.chunks,
        indexStatus: inferIndexStatus(info),
        uploadPending: false
      }
      const pendingIndex = attachments.value.findIndex(item => item.id === pendingId)
      if (pendingIndex >= 0) attachments.value.splice(pendingIndex, 1, uploaded)
      else attachments.value.push(uploaded)
      if (indexStatus(uploaded) === 'indexing') scheduleIndexPolling(uploaded)
      ElMessage.success(`${info.originalName || file.name} 已加入工作区`)
    } catch (error) {
      attachments.value = attachments.value.filter(item => item.id !== pendingId)
      ElMessage.error(error.message || `文件 ${file.name} 上传失败`)
    }
  }
}

function removeAttachment(id) {
  const target = attachments.value.find(file => file.id === id)
  if (target?.fileId) stopIndexPolling(target.fileId)
  attachments.value = attachments.value.filter(file => file.id !== id)
}

async function openCitation(citation) {
  citationPreview.open = true
  citationPreview.loading = true
  citationPreview.name = citation?.name || '文件引用'
  citationPreview.location = citation?.pageLabel || ''
  citationPreview.content = citation?.snippet || ''
  citationPreview.error = ''
  if (!citation?.fileId) {
    citationPreview.loading = false
    return
  }
  try {
    const response = await fetch(`${import.meta.env.VITE_APP_BASE_API || ''}/system/ai/workspace/files/${encodeURIComponent(citation.fileId)}/content`, { headers: apiHeaders() })
    const payload = await response.json()
    if (!response.ok || payload.code !== 200) throw new Error(payload.msg || '文件读取失败')
    const source = String(payload.data?.content || '')
    const start = Number(citation.startOffset)
    const end = Number(citation.endOffset)
    if (Number.isFinite(start) && start >= 0 && Number.isFinite(end) && end > start) {
      const from = Math.max(0, start - 500)
      const to = Math.min(source.length, end + 500)
      citationPreview.content = source.slice(from, to)
      citationPreview.location = `${citation.pageLabel || '文件片段'} · 原文 ${from}-${to}`
    } else {
      citationPreview.content = source || citation.snippet || '文件没有可预览的文本内容'
    }
  } catch (error) {
    citationPreview.error = error?.message || '文件读取失败'
  } finally {
    citationPreview.loading = false
  }
}

function closeCitation() {
  citationPreview.open = false
}

function formatSize(size) {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function indexStatus(file) {
  const raw = String(file?.indexStatus || file?.ragStatus || file?.embeddingStatus || '').toLowerCase()
  if (/pending|queue|indexing|processing|running|building|embedding/.test(raw)) return 'indexing'
  if (/error|fail|timeout/.test(raw)) return 'error'
  if (/skip|unsupported|unavailable|empty/.test(raw)) return 'unsupported'
  if (/ready|complete|completed|success|succeed|indexed|done/.test(raw)) return 'ready'
  if (file?.uploadPending) return 'indexing'
  if (file?.textExtracted === false || file?.extracted === false && file?.extractMessage) return 'unsupported'
  if (file?.textExtracted === true || file?.extracted === true || file?.indexedChunks !== undefined) return 'ready'
  return 'indexing'
}

function indexStatusLabel(file) {
  if (indexStatus(file) === 'unsupported' && /^image\//i.test(String(file?.contentType || ''))) return '图片可分析'
  return ({ indexing: '索引中', ready: '索引完成', error: '索引失败', unsupported: '无法索引' }[indexStatus(file)] || '索引中')
}

function inferIndexStatus(info) {
  const status = indexStatus(info)
  // 保留后端显式异步状态；旧版同步接口有 textExtracted 时直接视为完成。
  return status === 'indexing' && info?.textExtracted === true ? 'ready' : status
}

function indexStatusMessage(file) {
  return file?.indexMessage || file?.extractMessage || ({
    indexing: '索引任务正在后台处理',
    ready: file?.indexedChunks ? `已建立 ${file.indexedChunks} 个片段` : '文件已完成索引',
    error: '索引任务失败，可重新上传或重试',
    unsupported: /^image\//i.test(String(file?.contentType || '')) ? '图片无需建立文本索引，可直接交给视觉模型分析' : '未提取到可检索的文本内容'
  }[indexStatus(file)] || '等待索引状态')
}

function normalizeIndexTaskStatus(task) {
  const raw = String(task?.status || task?.indexStatus || '').toLowerCase()
  if (/queued|queue|pending|running|processing|building|embedding/.test(raw)) return 'indexing'
  if (/succeed|success|complete|ready|indexed|done/.test(raw)) return 'ready'
  if (/failed|error|timeout/.test(raw)) return 'error'
  if (/unsupported|not[_ -]?supported|skip|unavailable|empty/.test(raw)) return 'unsupported'
  return raw === 'not_indexed' ? 'indexing' : ''
}

function stopIndexPolling(fileId) {
  const timer = indexPollers.get(fileId)
  if (timer) clearTimeout(timer)
  indexPollers.delete(fileId)
}

function stopAllIndexPolling() {
  indexPollers.forEach(timer => clearTimeout(timer))
  indexPollers.clear()
}

function scheduleIndexPolling(file) {
  if (!file?.fileId || indexStatus(file) !== 'indexing') return
  const fileId = file.fileId
  stopIndexPolling(fileId)
  let attempts = 0
  const poll = async () => {
    const target = attachments.value.find(item => item.fileId === fileId)
    if (!target) {
      stopIndexPolling(fileId)
      return
    }
    attempts += 1
    try {
      const response = await fetch(`${import.meta.env.VITE_APP_BASE_API || ''}/system/ai/workspace/files/${encodeURIComponent(fileId)}/index`, { headers: apiHeaders() })
      const payload = await response.json()
      const task = payload?.data || {}
      const status = normalizeIndexTaskStatus(task)
      if (status) target.indexStatus = status
      if (task.taskId) target.indexTaskId = task.taskId
      if (task.chunks !== undefined) target.indexedChunks = task.chunks
      if (task.message) target.indexMessage = task.message
      if (status === 'ready' || status === 'error' || status === 'unsupported' || attempts >= 30) {
        if (attempts >= 30 && status !== 'ready' && status !== 'error' && status !== 'unsupported') {
          target.indexStatus = 'error'
          target.indexMessage = '索引状态查询超时，请稍后重试'
        }
        stopIndexPolling(fileId)
        return
      }
    } catch (error) {
      if (attempts >= 30) {
        target.indexStatus = 'error'
        target.indexMessage = '索引状态查询失败，请稍后重试'
        stopIndexPolling(fileId)
        return
      }
    }
    indexPollers.set(fileId, setTimeout(poll, 900))
  }
  poll()
}

async function ask(value = question.value) {
  if (!checkPermi(['ai:chat:send'])) return
  const text = value.trim()
  if (hasPendingUploads.value) {
    ElMessage.info('请等待文件上传和索引完成后再发送')
    return
  }
  if ((!text && !attachments.value.length) || loading.value) return
  question.value = ''
  const selectedFiles = attachments.value.map(file => ({ name: file.name, size: file.size, fileId: file.fileId || file.id }))
  runFiles.value = selectedFiles
  runSteps.value = []
  runStartedAt.value = Date.now()
  runFinishedAt.value = 0
  runId.value = ''
  runState.value = 'running'
  runRagState.value = selectedFiles.length || selectedKnowledgeBaseIds.value.length ? 'pending' : 'idle'
  runRetrievalMode.value = selectedFiles.length ? 'keyword' : 'none'
  ragStepAdded.value = false
  addRunStep('thinking', '理解问题', text || '准备分析上传文件。', 'running')
  if (selectedFiles.length) addRunStep('rag', '准备文件检索', '等待相关片段召回。', 'running')
  abortController.value = new AbortController()
  const prompt = buildPrompt(text)
  messages.value.push({ role: 'user', content: text || '请分析我上传的文件。', attachments: selectedFiles })
  // 流式回调会持续修改 content；必须使用响应式对象，否则 Vue 只会在 loading 结束时一次性刷新。
  const answer = reactive({ role: 'assistant', content: '', citations: [] })
  messages.value.push(answer)
  attachments.value = []
  loading.value = true
  connected.value = false
  toolStatus.value = ''
  const current = conversations.value.find(item => item.id === conversationId.value)
  if (current && (!current.title || current.title === '新对话')) current.title = text.slice(0, 42) || selectedFiles[0]?.name || '文件分析'
  await nextTick(scrollToBottom)

  try {
    const uploadedFileIds = selectedFiles.map(file => file.fileId).filter(Boolean)
    const request = uploadedFileIds.length
      ? { url: `${import.meta.env.VITE_APP_BASE_API || ''}/system/ai/workspace/analyze`, options: { method: 'POST', headers: { ...apiHeaders(), 'Content-Type': 'application/json', Accept: 'text/event-stream' }, body: JSON.stringify({ fileIds: uploadedFileIds, knowledgeBaseIds: selectedKnowledgeBaseIds.value, question: text || '请分析这些文件。', model: selectedModel.value, reasoningEffort: selectedReasoning.value, conversationId: conversationId.value }) } }
      : (() => {
          const params = new URLSearchParams({ question: prompt, conversationId: conversationId.value })
          if (selectedModel.value) params.set('model', selectedModel.value)
          if (selectedReasoning.value) params.set('reasoningEffort', selectedReasoning.value)
          if (selectedKnowledgeBaseIds.value.length) params.set('knowledgeBaseIds', selectedKnowledgeBaseIds.value.join(','))
          return { url: `${import.meta.env.VITE_APP_BASE_API || ''}/system/ai/chat?${params.toString()}`, options: { headers: { ...apiHeaders(), Accept: 'text/event-stream' } } }
        })()
    const response = await fetch(request.url, { ...request.options, signal: abortController.value.signal })
    if (!response.ok || !response.body) throw new Error('AI 接口连接失败')
    connected.value = true
    addRunStep('connected', '已连接模型', selectedModelLabel.value, 'done')
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let streamEnded = false
    while (true) {
      const { value: chunk, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(chunk, { stream: true })
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() || ''
      for (const frame of frames) {
        let event = 'message'
        const data = []
        frame.split(/\r?\n/).forEach(line => {
          if (line.startsWith('event:')) event = line.slice(6).trim()
          if (line.startsWith('data:')) data.push(line.slice(5).trim())
        })
        const payload = data.join('\n')
        handleStreamEvent(event, payload, answer)
        if (event === 'done' || event === 'error') streamEnded = true
        // 同一个网络块可能携带多个 SSE 事件，逐事件让 Vue 刷新，避免内容一次性出现。
        await nextTick(scrollToBottom)
        if (streamEnded) break
      }
      if (streamEnded) {
        break
      }
    }
    if (buffer.trim() && !streamEnded) {
      let event = 'message'
      const data = []
      buffer.split(/\r?\n/).forEach(line => {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        if (line.startsWith('data:')) data.push(line.slice(5).trim())
      })
      handleStreamEvent(event, data.join('\n'), answer)
      await nextTick(scrollToBottom)
    }
  } catch (error) {
    if (error?.name === 'AbortError') {
      runState.value = 'cancelled'
      addRunStep('cancelled', '已停止执行', '用户结束了本次 Agent 运行。', 'done')
      if (!answer.content) answer.content = '本次执行已停止。'
    } else {
      runState.value = 'error'
      addRunStep('error', '执行失败', error?.message || 'AI 接口连接失败', 'error')
      if (!answer.content) answer.content = 'AI 暂时无法连接，请确认模型已配置并启动后端服务。'
    }
  } finally {
    if (runState.value === 'done' && !answer.content.trim()) answer.content = '模型没有返回内容，请重试。'
    loading.value = false
    toolStatus.value = ''
    runFinishedAt.value = Date.now()
    abortController.value = null
    if (runState.value === 'running') {
      runState.value = 'done'
      addRunStep('done', '完成', '回答已生成并保存到当前会话。', 'done')
    }
  }
}

function stopRun() {
  const currentRunId = runId.value
  if (currentRunId) {
    fetch(`${import.meta.env.VITE_APP_BASE_API || ''}/system/ai/runs/${encodeURIComponent(currentRunId)}/cancel`, {
      method: 'POST',
      headers: apiHeaders()
    }).catch(() => {})
  }
  if (abortController.value) abortController.value.abort()
}

function resetRun() {
  runState.value = 'idle'
  runSteps.value = []
  runFiles.value = []
  runCitations.value = []
  runRagState.value = 'idle'
  runRetrievalMode.value = 'none'
  runRetrievalTrace.value = ''
  ragStepAdded.value = false
  runStartedAt.value = 0
  runFinishedAt.value = 0
  runId.value = ''
  abortController.value = null
}

function addRunStep(type, title, detail, state = 'done') {
  runSteps.value.push({ type, title, detail: String(detail || ''), state, at: Date.now() })
  if (runSteps.value.length > 20) runSteps.value.shift()
}

function parseEventPayload(value) {
  if (!value) return {}
  try {
    const parsed = JSON.parse(value)
    if (parsed && typeof parsed === 'object') return parsed
  } catch (error) { /* 普通文本事件 */ }
  return { text: value, message: value }
}

function handleStreamEvent(event, rawPayload, answer) {
  const payload = parseEventPayload(rawPayload)
  const text = payload.text ?? payload.message ?? payload.content ?? rawPayload
  const detectedMode = extractRetrievalMode(payload)
  if (detectedMode) runRetrievalMode.value = detectedMode
  if (event === 'retrieval') {
    const trace = payload.trace || {}
    runRagState.value = payload.count ? 'ready' : 'empty'
    runRetrievalTrace.value = `关键词 ${trace.keywordCandidates || 0} · 向量 ${trace.vectorCandidates || 0} · 返回 ${payload.count || 0} · ${trace.elapsedMs || 0} ms`
    addRunStep('retrieval', '知识检索', runRetrievalTrace.value, 'done')
    if (trace.warnings?.length) addRunStep('retrieval-warning', '检索提示', trace.warnings.join('；'), 'done')
    return
  }
  if (event === 'status' || event === 'retrieval') {
    const statusText = String(text || rawPayload || '')
    if (/混合/.test(statusText)) runRetrievalMode.value = 'hybrid'
    else if (/向量/.test(statusText)) runRetrievalMode.value = 'vector'
    else if (/关键词/.test(statusText)) runRetrievalMode.value = 'keyword'
  }
  if (event === 'status' && /检索|召回|retriev|embedding|vector|向量/i.test(String(text || ''))) {
    runRagState.value = 'searching'
  }
  if (isCitationEvent(event)) {
    const citations = extractCitations(event, payload, rawPayload)
    citations.forEach(citation => addCitation(citation, answer))
    if (citations.length) {
      runRagState.value = 'ready'
      if (!detectedMode && runRetrievalMode.value === 'none') runRetrievalMode.value = 'keyword'
      if (!ragStepAdded.value) {
        addRunStep('rag', '文件检索完成', `${retrievalModeLabel.value}，已命中 ${citations.length} 个片段。`, 'done')
        ragStepAdded.value = true
      }
      citations.forEach(citation => addRunStep('citation', '引用文件', formatCitationDetail(citation), 'done'))
    }
    return
  }
  // Some providers include citations alongside the final message instead of
  // emitting a dedicated citation event. Keep that shape compatible too.
  extractCitationsFromEnvelope(payload).forEach(citation => addCitation(citation, answer))
  if (event === 'run') {
    runId.value = payload.runId || payload.id || ''
    addRunStep('run', 'Agent 已启动', runId.value ? `运行 ID：${runId.value}` : '已建立运行上下文', 'done')
    return
  }
  if (event === 'message' || event === 'token' || event === 'delta') {
    answer.content += text
    return
  }
  if (event === 'status' || event === 'thinking') {
    toolStatus.value = text
    addRunStep(event, payload.title || 'Agent 状态', text, 'running')
    return
  }
  if (event === 'tool' || event === 'tool_start' || event === 'tool_call') {
    const name = payload.name || payload.tool || payload.title || '管理工具'
    addRunStep('tool', `调用工具：${name}`, payload.detail || text || '正在读取工具结果', 'running')
    toolStatus.value = payload.detail || `正在调用 ${name}`
    return
  }
  if (event === 'tool_result' || event === 'tool_end') {
    const name = payload.name || payload.tool || '管理工具'
    addRunStep('tool_result', `工具完成：${name}`, payload.detail || text || '已返回工具结果', 'done')
    toolStatus.value = payload.detail || '工具结果已返回'
    return
  }
  if (event === 'error') {
    runState.value = 'error'
    if (runRagState.value === 'pending' || runRagState.value === 'searching') runRagState.value = 'error'
    addRunStep('error', '执行失败', text, 'error')
    answer.content += `\n\n${text}`
    return
  }
  if (event === 'done') {
    extractCitationsFromEnvelope(payload).forEach(citation => addCitation(citation, answer))
    finishRetrievalState()
    addRunStep('done', '收到完成信号', 'Agent 已结束本次流式运行。', 'done')
    return
  }
  if (text) answer.content += text
}

function isCitationEvent(event) {
  return ['citation', 'citations', 'file_ref', 'file-reference', 'file_reference', 'reference', 'references', 'source', 'sources'].includes(String(event || '').toLowerCase())
}

function extractCitationsFromEnvelope(payload) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return []
  const values = []
  for (const key of ['citations', 'references', 'sources', 'fileRefs', 'file_refs', 'fileReferences', 'file_references']) {
    if (payload[key]) values.push(...flattenCitationValues(payload[key]))
  }
  return normalizeCitations(values)
}

function extractCitations(event, payload, rawPayload) {
  const values = []
  if (Array.isArray(payload)) values.push(...payload)
  else if (payload && typeof payload === 'object') {
    for (const key of ['citations', 'references', 'sources', 'fileRefs', 'file_refs', 'fileReferences', 'file_references']) {
      if (payload[key]) values.push(...flattenCitationValues(payload[key]))
    }
    for (const key of ['citation', 'reference', 'source', 'fileRef', 'file_ref', 'data']) {
      if (payload[key]) values.push(...flattenCitationValues(payload[key]))
    }
    // Direct payload: { fileName, page, snippet }.
    if (looksLikeCitation(payload)) values.push(payload)
  }
  if (!values.length && typeof rawPayload === 'string' && rawPayload.trim()) {
    values.push({ snippet: rawPayload.trim() })
  }
  return normalizeCitations(values)
}

function flattenCitationValues(value) {
  if (Array.isArray(value)) return value.flatMap(flattenCitationValues)
  if (value && typeof value === 'object') return [value]
  if (typeof value === 'string' && value.trim()) {
    try {
      const parsed = JSON.parse(value)
      return flattenCitationValues(parsed)
    } catch (error) {
      return [{ snippet: value.trim() }]
    }
  }
  return []
}

function looksLikeCitation(value) {
  return ['fileName', 'filename', 'originalName', 'documentName', 'sourceName', 'file', 'name', 'source', 'page', 'pageNumber', 'pageIndex', 'pageNo', 'snippet', 'quote', 'excerpt', 'chunk', 'passage', 'fileId', 'citationId', 'chunkId'].some(key => value[key] !== undefined && value[key] !== null)
}

function normalizeCitations(values) {
  const result = []
  const seen = new Set()
  values.forEach(value => {
    if (!value || typeof value !== 'object') return
    const fileObject = value.file && typeof value.file === 'object' ? value.file : null
    const sourceObject = value.source && typeof value.source === 'object' ? value.source : null
    const name = firstText(value.fileName, value.filename, value.originalName, value.documentName, value.sourceName,
      fileObject?.originalName, fileObject?.name, sourceObject?.originalName, sourceObject?.name,
      typeof value.file === 'string' ? value.file : '', typeof value.source === 'string' ? value.source : '', value.name, value.title) || '引用文件'
    const page = firstText(value.page, value.pageNumber, value.pageNo, value.pageIndex, value.location, value.line, value.lineStart, value.range)
    const chunkIndex = firstText(value.chunkIndex, value.segmentIndex, value.chunkNo, value.segment)
    const startOffset = firstText(value.startOffset, value.start, value.offsetStart)
    const endOffset = firstText(value.endOffset, value.end, value.offsetEnd)
    const snippet = firstText(value.snippet, value.quote, value.excerpt, value.chunk, value.passage, value.text, value.content, value.detail)
    const fileId = firstText(value.fileId, value.documentId, value.sourceId)
    const id = firstText(value.citationId, value.referenceId, value.chunkId, value.id)
    const key = id || `${name}|${page || ''}|${snippet || ''}`
    if (seen.has(key)) return
    seen.add(key)
    result.push({
      key,
      fileId,
      name,
      chunkIndex,
      startOffset,
      endOffset,
      pageLabel: formatCitationLocation(page, chunkIndex, startOffset, endOffset),
      snippet: snippet ? String(snippet).trim() : ''
    })
  })
  return result
}

function normalizeRetrievalMode(value) {
  if (value === undefined || value === null) return ''
  const text = String(value).trim().toLowerCase()
  if (!text) return ''
  if (/hybrid|mixed|混合/.test(text)) return 'hybrid'
  if (/vector|embedding|semantic|向量|语义/.test(text)) return 'vector'
  if (/keyword|bm25|lexical|关键词|全文/.test(text)) return 'keyword'
  if (/none|disabled|off|未启用|无/.test(text)) return 'none'
  return ''
}

function extractRetrievalMode(payload) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return ''
  const retrieval = payload.retrieval && typeof payload.retrieval === 'object' ? payload.retrieval : null
  for (const value of [payload.retrievalMode, payload.searchMode, payload.ragMode, payload.retrieval_type,
    payload.vectorMode, payload.mode, retrieval?.mode, retrieval?.type, retrieval?.retrievalMode]) {
    const normalized = normalizeRetrievalMode(value)
    if (normalized) return normalized
  }
  return ''
}

function finishRetrievalState() {
  if (runRagState.value !== 'pending' && runRagState.value !== 'searching') return
  if (runCitations.value.length) {
    runRagState.value = 'ready'
    if (runRetrievalMode.value === 'none') runRetrievalMode.value = 'keyword'
    return
  }
  runRagState.value = 'empty'
  if (!ragStepAdded.value) {
    addRunStep('rag', '文件检索完成', '没有命中与问题直接相关的片段，已交给 Agent 综合判断。', 'done')
    ragStepAdded.value = true
  }
}

function firstText(...values) {
  return values.find(value => value !== undefined && value !== null && String(value).trim() !== '')
}

function formatPageLabel(value) {
  if (value === undefined || value === null || String(value).trim() === '') return ''
  const text = String(value).trim()
  if (/页|page|行|line|位置|line/i.test(text)) return text
  return /^\d+$/.test(text) ? `第 ${text} 页` : text
}

function formatCitationLocation(page, chunkIndex, startOffset, endOffset) {
  const pageLabel = formatPageLabel(page)
  if (pageLabel) return pageLabel
  if (chunkIndex !== undefined && chunkIndex !== null && String(chunkIndex).trim() !== '') {
    const numeric = Number(chunkIndex)
    const label = Number.isFinite(numeric) ? `片段 ${numeric + 1}` : `片段 ${String(chunkIndex).trim()}`
    if (startOffset !== undefined && endOffset !== undefined) return `${label} · 字符 ${startOffset}-${endOffset}`
    return label
  }
  if (startOffset !== undefined && endOffset !== undefined) return `字符 ${startOffset}-${endOffset}`
  return ''
}

function formatCitationDetail(citation) {
  return [citation.name, citation.pageLabel, citation.snippet].filter(Boolean).join(' · ') || '已定位相关文件片段'
}

function addCitation(citation, answer) {
  if (!citation) return
  runRagState.value = 'ready'
  if (runRetrievalMode.value === 'none') runRetrievalMode.value = 'keyword'
  const exists = runCitations.value.some(item => item.key === citation.key)
  if (!exists) runCitations.value.push(citation)
  if (answer) {
    if (!Array.isArray(answer.citations)) answer.citations = []
    if (!answer.citations.some(item => item.key === citation.key)) answer.citations.push(citation)
  }
}

function buildPrompt(text) {
  return text || '请分析我上传的文件。'
}

function modelStorageKey(id) {
  return `zzl-ai-workbench-model:${id}`
}

function reasoningStorageKey(id) {
  return `zzl-ai-workbench-reasoning:${id}`
}

function resolveModelChoice(value) {
  const raw = String(value || '').trim()
  if (!raw) return null
  return modelChoices.value.find(item => item.value === raw)
    || modelChoices.value.find(item => item.baseId === raw)
    || modelChoices.value.find(item => item.modelName === raw)
    || null
}

function restoreConversationModel() {
  const current = conversations.value.find(item => item.id === conversationId.value)
  const candidates = [current?.model, localStorage.getItem(modelStorageKey(conversationId.value))]
  const restored = candidates.map(resolveModelChoice).find(Boolean)
  if (restored) selectedModel.value = restored.value
  restoreConversationReasoning()
}

function restoreConversationReasoning() {
  const current = conversations.value.find(item => item.id === conversationId.value)
  const configured = selectedModelConfig.value?.reasoningEffort
  const saved = localStorage.getItem(reasoningStorageKey(conversationId.value))
  const candidate = String(saved || configured || 'auto').trim().toLowerCase()
  selectedReasoning.value = reasoningChoices.value.some(item => item.value === candidate) ? candidate : 'auto'
}

function modelDisplayLabel(value) {
  return resolveModelChoice(value)?.label || value || '本地摘要模式'
}

function reasoningDisplayLabel(value) {
  return ({ auto: '自动', low: '低', medium: '中', high: '高' }[String(value || 'auto').toLowerCase()] || '自动')
}

function providerDisplayLabel(value) {
  return ({ openai: 'OpenAI', claude: 'Claude', anthropic: 'Claude', deepseek: 'DeepSeek', ollama: 'Ollama', custom: '通用' }[String(value || '').toLowerCase()] || '自动')
}

function readMessages(id) {
  try { return JSON.parse(localStorage.getItem(`zzl-ai-workbench:${id}`) || '[]') } catch (error) { return [] }
}

function saveMessages() {
  localStorage.setItem(`zzl-ai-workbench:${conversationId.value}`, JSON.stringify(messages.value.slice(-60)))
}

function scrollToBottom() {
  if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight
}

function escapeHtml(value) {
  return String(value || '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]))
}

function renderMarkdown(value) {
  let html = escapeHtml(value)
  html = html.replace(/```([\s\S]*?)```/g, '<pre><code>$1</code></pre>')
  html = html.replace(/^### (.*)$/gm, '<h4>$1</h4>').replace(/^## (.*)$/gm, '<h3>$1</h3>').replace(/^# (.*)$/gm, '<h2>$1</h2>')
  html = html.replace(/^[-*] (.*)$/gm, '<li>$1</li>').replace(/(<li>[\s\S]*?<\/li>)/g, '<ul>$1</ul>')
  html = html.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>').replace(/`([^`]+)`/g, '<code>$1</code>')
  return html.replace(/\n/g, '<br>')
}
</script>

<style scoped lang="scss">
.ai-workbench {
  --ink: #fff9f2;
  --surface: #fffdf9;
  --surface-raised: #ffffff;
  --surface-soft: #eefaf5;
  --line: #e9e3ee;
  --text: #2b3044;
  --muted: #7d7e91;
  --teal: #45b99e;
  --teal-deep: #2f9b86;
  --amber: #e99d56;
  --danger: #df7070;
  min-height: calc(100vh - 84px);
  padding: clamp(16px, 2.4vw, 32px);
  color: var(--text);
  background: radial-gradient(circle at 12% 4%, rgba(143, 225, 199, .26), transparent 32%),
    radial-gradient(circle at 92% 0%, rgba(255, 205, 143, .24), transparent 26%), var(--ink);
  font-family: "Avenir Next", "Noto Sans SC", "PingFang SC", "Segoe UI", sans-serif;
}
.workbench-header { position: relative; display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; margin-bottom: 20px; padding-bottom: 20px; }
.workbench-header::after { position: absolute; right: 0; bottom: 0; left: 0; height: 1px; content: ''; background: linear-gradient(90deg, var(--teal), rgba(130, 227, 213, .12), transparent); }
.header-copy h1 { margin: 0 0 7px; color: var(--text); font-size: clamp(26px, 3vw, 36px); font-weight: 650; letter-spacing: -.035em; line-height: 1.12; }
.header-copy p:not(.eyebrow) { max-width: 640px; margin: 0; color: var(--muted); font-size: 13px; line-height: 1.65; }
.eyebrow { margin: 0 0 8px; color: var(--teal); font-size: 11px; font-weight: 700; letter-spacing: .08em; }
.header-actions { display: flex; align-items: center; gap: 9px; }
.model-select { width: 190px; }
.knowledge-select { width: 170px; }
.reasoning-select { width: 138px; }
.model-lock-note { max-width: 180px; color: var(--muted); font-size: 11px; line-height: 1.35; }
.workspace-grid { display: grid; grid-template-columns: 240px minmax(0, 1fr) 270px; gap: 14px; min-height: calc(100vh - 198px); }
.panel { border: 1px solid var(--line); border-radius: 12px; background: rgba(255, 253, 249, .94); box-shadow: 0 20px 50px rgba(77, 65, 99, .10); }
.session-sidebar, .capability-panel, .shortcut-panel, .notice-panel { padding: 16px; }
.session-sidebar { display: flex; flex-direction: column; min-height: 0; }
.sidebar-heading, .chat-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.sidebar-heading { margin-bottom: 12px; }
.sidebar-heading strong, .chat-heading strong { color: var(--text); font-size: 13px; font-weight: 650; }
.conversation-search { margin-bottom: 12px; }
.conversation-list { flex: 1; overflow: auto; }
.conversation-item { position: relative; display: flex; align-items: center; width: 100%; gap: 8px; padding: 11px 9px 11px 12px; border: 1px solid transparent; border-radius: 8px; color: var(--muted); text-align: left; background: transparent; cursor: pointer; }
.conversation-item:hover, .conversation-item.active { border-color: rgba(130, 227, 213, .24); color: var(--text); background: rgba(130, 227, 213, .08); }
.conversation-item.active::before { position: absolute; top: 9px; bottom: 9px; left: 0; width: 2px; border-radius: 2px; content: ''; background: var(--teal); }
.conversation-icon { flex: 0 0 auto; color: var(--teal); font-size: 13px; }
.conversation-meta { display: grid; min-width: 0; flex: 1; gap: 4px; }
.conversation-meta strong, .conversation-meta small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conversation-meta strong { color: inherit; font-size: 12px; font-weight: 550; }
.conversation-meta small { color: #6f8f95; font-size: 10px; }
.delete-button { flex: 0 0 auto; color: #6f8f95 !important; }
.empty-sidebar, .sidebar-footer { color: #6f8f95; font-size: 11px; text-align: center; }
.sidebar-footer { padding-top: 14px; border-top: 1px solid rgba(37, 69, 80, .68); }
.chat-panel { display: flex; flex-direction: column; min-width: 0; min-height: 0; padding: 18px; }
.chat-heading { padding-bottom: 13px; border-bottom: 1px solid var(--line); }
.chat-heading div { display: grid; gap: 4px; }
.chat-heading span { color: var(--teal); font-size: 11px; }
.message-list { flex: 1; overflow-y: auto; padding: 22px 8px; scrollbar-color: #b9dcd2 transparent; }
.welcome-state { position: relative; display: grid; place-items: center; max-width: 580px; min-height: 390px; margin: auto; color: var(--muted); text-align: center; }
.welcome-orb { position: relative; display: grid; place-items: center; width: 66px; height: 66px; margin-bottom: 17px; border: 1px solid rgba(69, 185, 158, .58); border-radius: 18px; color: var(--teal); font-size: 26px; background: #e8faf2; box-shadow: 0 0 0 8px rgba(69, 185, 158, .08), 0 16px 40px rgba(111, 91, 125, .12); }
.welcome-orb::before, .welcome-orb::after { position: absolute; border: 1px solid rgba(130, 227, 213, .25); border-radius: 50%; content: ''; }
.welcome-orb::before { width: 90px; height: 90px; }
.welcome-orb::after { width: 116px; height: 116px; border-color: rgba(130, 227, 213, .12); }
.welcome-state h2 { margin: 0 0 9px; color: var(--text); font-size: 23px; font-weight: 600; letter-spacing: -.025em; }
.welcome-state p { max-width: 480px; margin: 0; line-height: 1.75; }
.suggestions { display: flex; flex-wrap: wrap; justify-content: center; gap: 8px; margin-top: 24px; }
.suggestions button, .shortcut-panel button { padding: 8px 11px; border: 1px solid var(--line); border-radius: 8px; color: #a8c1c1; font-size: 12px; background: rgba(19, 52, 64, .52); cursor: pointer; transition: border-color .2s, background .2s, color .2s; }
.suggestions button:hover, .shortcut-panel button:hover { border-color: var(--teal); color: var(--teal); background: rgba(130, 227, 213, .08); }
.message-row { display: flex; align-items: flex-start; gap: 10px; max-width: 84%; margin-bottom: 17px; }
.message-row.user { flex-direction: row-reverse; margin-left: auto; }
.message-avatar { display: grid; place-items: center; width: 28px; height: 28px; flex: 0 0 auto; border: 1px solid rgba(69, 185, 158, .42); border-radius: 8px; color: var(--teal-deep); font-size: 10px; font-weight: 800; background: #e8faf2; }
.message-row.user .message-avatar { border-color: rgba(233, 157, 86, .55); color: #c97932; background: #fff0dc; }
.message-bubble { min-width: 0; padding: 12px 14px; border: 1px solid var(--line); border-radius: 10px; color: #4c5063; line-height: 1.75; background: #ffffff; }
.message-row.user .message-bubble { border-color: rgba(233, 157, 86, .28); color: #67452b; background: #fff0dc; }
.message-content { white-space: pre-wrap; }
.message-model-meta { display: block; margin-top: 7px; color: #7da29d; font-size: 10px; line-height: 1.4; }
.message-files, .attachment-strip { display: flex; flex-wrap: wrap; gap: 6px; }
.message-files { margin-bottom: 7px; }
.file-chip, .attachment-item { padding: 5px 8px; border: 1px solid rgba(130, 227, 213, .22); border-radius: 6px; color: var(--teal); font-size: 11px; background: rgba(130, 227, 213, .08); }
.message-row.user .file-chip { border-color: rgba(246, 189, 107, .24); color: #ffe1a9; background: rgba(246, 189, 107, .12); }
.message-citations { display: grid; gap: 7px; margin-top: 12px; padding-top: 10px; border-top: 1px solid rgba(130, 227, 213, .15); }
.citation-heading { color: var(--teal); font-size: 10px; font-weight: 700; letter-spacing: .05em; }
.citation-card { display: flex; gap: 8px; padding: 8px 9px; border: 1px solid rgba(130, 227, 213, .18); border-radius: 7px; color: #a8c1c1; background: rgba(7, 21, 28, .42); cursor: pointer; transition: border-color .2s, background .2s; }
.citation-card:hover, .citation-card:focus-visible, .run-citation-card:hover, .run-citation-card:focus-visible { border-color: var(--teal); background: rgba(130, 227, 213, .08); outline: none; }
.citation-marker { display: grid; place-items: center; width: 18px; height: 18px; flex: 0 0 auto; border-radius: 5px; color: var(--ink); font-size: 10px; font-weight: 800; background: var(--teal); }
.citation-content { display: grid; min-width: 0; gap: 3px; }
.citation-content strong { overflow: hidden; color: #d9e9e7; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.citation-content small { color: var(--teal); font-size: 10px; }
.citation-content p { margin: 0; color: #78969b; font-size: 11px; line-height: 1.45; white-space: pre-wrap; }
.markdown-body :deep(h2), .markdown-body :deep(h3), .markdown-body :deep(h4) { margin: 5px 0; color: var(--teal); }
.markdown-body :deep(ul) { padding-left: 18px; margin: 4px 0; }
.markdown-body :deep(pre) { padding: 10px; overflow: auto; border: 1px solid var(--line); border-radius: 7px; color: #f8fbfa; background: #2b3044; }
.markdown-body :deep(code) { padding: 1px 4px; border-radius: 4px; color: #f8d28f; background: rgba(246, 189, 107, .12); }
.markdown-body :deep(pre code) { padding: 0; background: transparent; }
.tool-status { margin: 6px 42px; color: var(--amber); font-size: 11px; }
.attachment-strip { margin: 0 0 10px; }
.attachment-item { display: inline-flex; align-items: center; gap: 7px; }
.attachment-item small { color: #6f8f95; }
.attachment-item button { border: 0; color: #8ca8ac; background: transparent; cursor: pointer; }
.index-status { padding: 2px 6px; border-radius: 5px; font-size: 10px; line-height: 1.4; }
.index-status-indexing { color: var(--amber); background: rgba(246, 189, 107, .12); }
.index-status-ready { color: var(--teal); background: rgba(130, 227, 213, .10); }
.index-status-error { color: var(--danger); background: rgba(255, 133, 128, .10); }
.index-status-unsupported { color: #a4a9ad; background: rgba(164, 169, 173, .10); }
.composer { display: flex; align-items: flex-end; gap: 8px; padding-top: 13px; border-top: 1px solid var(--line); }
.composer :deep(.el-textarea__inner) { min-height: 72px !important; border: 1px solid var(--line); border-radius: 9px; color: var(--text); background: #ffffff; box-shadow: none; }
.composer :deep(.el-textarea__inner::placeholder) { color: #66858b; }
.send-button { height: 72px; padding: 0 20px; border: 0; border-radius: 9px; color: var(--ink); background: var(--teal); }
.send-button:hover { background: #a1f0e5; }
.composer-tip { padding-top: 8px; color: #65838a; font-size: 10px; text-align: right; }
.capability-sidebar { display: grid; align-content: start; gap: 14px; }
.capability-item { display: flex; gap: 10px; padding: 10px 0; border-bottom: 1px solid rgba(37, 69, 80, .72); }
.capability-item:last-child { border-bottom: 0; }
.capability-icon { display: grid; place-items: center; width: 30px; height: 30px; flex: 0 0 auto; border: 1px solid rgba(130, 227, 213, .28); border-radius: 8px; color: var(--teal); background: rgba(130, 227, 213, .08); }
.capability-item div { display: grid; gap: 3px; }
.capability-item small, .notice-panel p, .notice-panel span { color: var(--muted); font-size: 11px; line-height: 1.6; }
.live-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--teal); box-shadow: 0 0 0 4px rgba(130, 227, 213, .10); }
.shortcut-panel { display: grid; gap: 8px; }
.shortcut-panel .sidebar-heading { margin-bottom: 2px; }
.shortcut-panel button { text-align: left; }
.notice-panel strong { display: block; margin-bottom: 6px; color: var(--text); }
.notice-panel p { margin: 0 0 8px; }
.notice-panel span { color: var(--teal); }
.run-panel { padding: 16px; }
.run-panel .sidebar-heading { margin-bottom: 7px; }
.run-heading-tags { display: flex; align-items: center; gap: 6px; }
.run-meta { margin-bottom: 12px; color: #6f8f95; font-size: 10px; }
.retrieval-status { display: grid; grid-template-columns: auto auto; gap: 2px 8px; margin: 0 0 13px; padding: 9px 10px; border: 1px solid rgba(130, 227, 213, .18); border-radius: 8px; background: rgba(130, 227, 213, .06); }
.retrieval-status-label { color: var(--teal); font-size: 10px; font-weight: 700; }
.retrieval-status strong { color: var(--text); font-size: 10px; }
.retrieval-status small { grid-column: 1 / -1; color: var(--muted); font-size: 10px; line-height: 1.4; }
.run-timeline { position: relative; display: grid; gap: 0; padding-left: 4px; }
.run-timeline::before { position: absolute; top: 8px; bottom: 8px; left: 8px; width: 1px; content: ''; background: #cfe6df; }
.run-step { position: relative; display: flex; gap: 9px; min-width: 0; padding: 0 0 13px; }
.run-step:last-child { padding-bottom: 2px; }
.run-step-dot { z-index: 1; width: 9px; height: 9px; flex: 0 0 auto; margin-top: 4px; border: 2px solid #47707a; border-radius: 50%; background: var(--surface); }
.run-step.running .run-step-dot { border-color: var(--amber); background: var(--amber); box-shadow: 0 0 0 4px rgba(246, 189, 107, .11); }
.run-step.done .run-step-dot { border-color: var(--teal); background: var(--teal); }
.run-step.error .run-step-dot { border-color: var(--danger); background: var(--danger); }
.run-step-content { display: grid; min-width: 0; gap: 2px; }
.run-step-content strong { overflow: hidden; color: #d9e9e7; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.run-step-content small { overflow: hidden; color: #78969b; font-size: 10px; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.run-empty { color: #6f8f95; font-size: 11px; line-height: 1.65; }
.run-files { display: grid; gap: 6px; margin-top: 14px; padding-top: 12px; border-top: 1px solid rgba(37, 69, 80, .72); }
.run-files-label { color: var(--teal); font-size: 10px; font-weight: 700; }
.run-file-chip { overflow: hidden; padding: 5px 7px; border: 1px solid rgba(130, 227, 213, .16); border-radius: 6px; color: #a8c1c1; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; background: rgba(130, 227, 213, .06); }
.run-citations { display: grid; gap: 7px; margin-top: 13px; padding-top: 12px; border-top: 1px solid rgba(37, 69, 80, .72); }
.run-citation-card { display: grid; gap: 3px; padding: 8px; border: 1px solid rgba(130, 227, 213, .16); border-radius: 7px; background: rgba(7, 21, 28, .42); cursor: pointer; transition: border-color .2s, background .2s; }
.run-citation-card strong { overflow: hidden; color: #d9e9e7; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.run-citation-card small { color: var(--teal); font-size: 10px; }
.run-citation-card p { display: -webkit-box; overflow: hidden; margin: 0; color: #78969b; font-size: 10px; line-height: 1.4; -webkit-box-orient: vertical; -webkit-line-clamp: 3; }
.citation-preview-overlay { position: fixed; z-index: 30; inset: 0; display: grid; place-items: center; padding: 24px; background: rgba(43, 48, 68, .26); }
.citation-preview { display: grid; width: min(760px, 94vw); max-height: min(78vh, 720px); gap: 14px; padding: 18px; overflow: hidden; border-color: var(--teal); background: #fffdf9; box-shadow: 0 24px 70px rgba(77, 65, 99, .22); }
.citation-preview-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; }
.citation-preview-heading > div { display: grid; gap: 5px; min-width: 0; }
.citation-preview-heading strong { overflow: hidden; color: var(--text); font-size: 15px; text-overflow: ellipsis; white-space: nowrap; }
.citation-preview-heading small { color: var(--teal); font-size: 10px; }
.citation-preview-content { max-height: 60vh; margin: 0; padding: 14px; overflow: auto; border: 1px solid var(--line); border-radius: 8px; color: #4c5063; background: #fffaf4; font: 12px/1.65 "SFMono-Regular", Consolas, monospace; white-space: pre-wrap; }
.citation-preview-state { padding: 28px; color: var(--teal); text-align: center; }
.citation-preview-state.error { color: var(--danger); }
.ai-workbench :deep(.el-button) { --el-button-text-color: var(--muted); --el-button-hover-text-color: var(--teal); --el-button-hover-bg-color: rgba(130, 227, 213, .08); }
.ai-workbench :deep(.el-button--primary) { --el-button-bg-color: var(--teal); --el-button-border-color: var(--teal); --el-button-hover-bg-color: #a1f0e5; --el-button-hover-border-color: #a1f0e5; --el-button-text-color: var(--ink); }
.ai-workbench :deep(.el-tag) { border-color: rgba(130, 227, 213, .26); color: var(--teal); background: rgba(130, 227, 213, .08); }
.ai-workbench :deep(.el-input__wrapper), .ai-workbench :deep(.el-select__wrapper) { border: 1px solid var(--line); border-radius: 8px; background: #ffffff; box-shadow: none; }
.ai-workbench :deep(.el-input__inner), .ai-workbench :deep(.el-select__selected-item) { color: var(--text); }
.ai-workbench :deep(.el-input__inner::placeholder) { color: #66858b; }
@media (max-width: 1250px) { .workspace-grid { grid-template-columns: 210px minmax(0, 1fr); } .capability-sidebar { display: none; } }
@media (max-width: 760px) { .ai-workbench { padding: 12px; } .workbench-header { align-items: flex-start; flex-direction: column; } .header-actions { width: 100%; flex-wrap: wrap; } .model-select, .knowledge-select, .reasoning-select { flex: 1; min-width: 130px; } .workspace-grid { grid-template-columns: 1fr; min-height: auto; } .session-sidebar { min-height: 210px; } .conversation-list { max-height: 180px; } .message-row { max-width: 96%; } .composer { flex-wrap: wrap; } .composer :deep(.el-textarea) { flex: 1; min-width: 180px; } .send-button { height: 46px; } }
@media (prefers-reduced-motion: reduce) { * { scroll-behavior: auto !important; } }

/* 一屏模式：桌面端页面不滚动，只让消息区和必要的时间线内部滚动。 */
@media (min-width: 761px) {
  .ai-workbench { height: calc(100vh - 84px); min-height: 0; overflow: hidden; padding: 14px 22px 16px; box-sizing: border-box; }
  .workbench-header { flex: 0 0 auto; margin-bottom: 10px; padding-bottom: 10px; }
  .header-copy h1 { margin-bottom: 0; font-size: 25px; }
  .header-copy > p:not(.eyebrow) { display: none; }
  .eyebrow { margin-bottom: 4px; }
  .workspace-grid { grid-template-columns: 198px minmax(0, 1fr) 224px; gap: 11px; height: calc(100% - 64px); min-height: 0; }
  .session-sidebar, .chat-panel, .capability-sidebar { min-height: 0; }
  .session-sidebar { padding: 12px; }
  .session-sidebar .sidebar-footer { display: none; }
  .sidebar-heading { margin-bottom: 8px; }
  .conversation-search { margin-bottom: 8px; }
  .conversation-item { padding: 8px 7px 8px 10px; }
  .chat-panel { padding: 13px; }
  .chat-heading { padding-bottom: 8px; }
  .message-list { min-height: 0; padding: 12px 6px; }
  .welcome-state { min-height: 220px; }
  .welcome-orb { width: 54px; height: 54px; margin-bottom: 10px; font-size: 22px; }
  .welcome-state h2 { font-size: 19px; }
  .welcome-state p { max-width: 430px; font-size: 12px; }
  .suggestions { margin-top: 14px; }
  .message-row { max-width: 91%; margin-bottom: 10px; }
  .message-bubble { padding: 9px 12px; line-height: 1.6; }
  .attachment-strip { margin-bottom: 6px; }
  .composer { padding-top: 8px; }
  .composer :deep(.el-textarea__inner) { min-height: 52px !important; }
  .send-button { height: 52px; }
  .composer-tip { display: none; }
  .capability-sidebar { display: block; overflow: hidden; }
  .capability-sidebar > .capability-panel, .capability-sidebar > .shortcut-panel, .capability-sidebar > .notice-panel { display: none; }
  .run-panel { height: 100%; overflow: auto; box-sizing: border-box; padding: 12px; }
  .run-panel .sidebar-heading { margin-bottom: 5px; }
  .retrieval-status { margin-bottom: 9px; padding: 7px 8px; }
  .run-step { padding-bottom: 9px; }
  .run-files, .run-citations { margin-top: 9px; padding-top: 8px; }
}
@media (min-width: 761px) and (max-width: 1050px) {
  .workspace-grid { grid-template-columns: 180px minmax(0, 1fr); }
  .capability-sidebar { display: none; }
}
</style>

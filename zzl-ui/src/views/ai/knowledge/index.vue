<template>
  <div class="knowledge-page">
    <header class="page-heading">
      <div><h1>知识库</h1><span>常用资料，随时可问</span></div>
      <el-button v-hasPermi="['ai:knowledge:add']" type="primary" :icon="Plus" @click="openCreate">新建知识库</el-button>
    </header>
    <div class="knowledge-layout" v-loading="loading">
      <aside class="library-list">
        <el-input v-model="baseFilter" :prefix-icon="Search" clearable placeholder="查找知识库" />
        <div class="library-scroll">
          <button v-for="base in filteredBases" :key="base.id" class="library-item" :class="{ active: base.id === selectedBaseId }" @click="selectBase(base.id)">
            <span class="library-icon"><el-icon><Collection /></el-icon></span>
            <span><strong>{{ base.name }}</strong><small>{{ base.documentCount }} 个文件{{ base.enabled ? '' : ' · 已停用' }}</small></span>
          </button>
          <el-empty v-if="!filteredBases.length" :image-size="70" description="暂无知识库" />
        </div>
      </aside>
      <main v-if="selectedBase" class="library-content">
        <div class="library-heading">
          <div><h2>{{ selectedBase.name }}</h2><p v-if="selectedBase.description">{{ selectedBase.description }}</p></div>
          <el-button v-if="checkPermi(['ai:knowledge:edit', 'ai:knowledge:remove'])" :icon="EditPen" text @click="openEdit(selectedBase)">{{ checkPermi(['ai:knowledge:edit']) ? '编辑' : '管理' }}</el-button>
        </div>
        <div class="library-summary">
          <span><i class="dot mint" />{{ counts.ready }} 已就绪</span>
          <span v-if="counts.active"><i class="dot amber" />{{ counts.active }} 索引中</span>
          <span v-if="counts.failed"><i class="dot coral" />{{ counts.failed }} 失败</span>
          <span v-if="counts.stale"><i class="dot violet" />{{ counts.stale }} 待重建</span>
          <span class="summary-model">{{ embeddingLabel(selectedBase.settings) }}</span>
          <el-switch :disabled="!checkPermi(['ai:knowledge:edit'])" :model-value="selectedBase.enabled" inline-prompt active-text="启用" inactive-text="停用" @change="toggleBase" />
        </div>
        <el-tabs v-model="activeTab" class="knowledge-tabs">
          <el-tab-pane label="文档" name="documents">
            <div class="document-toolbar">
              <el-input v-model="documentFilter" :prefix-icon="Search" clearable placeholder="查找文件" />
              <el-button v-hasPermi="['ai:knowledge:index']" :icon="Refresh" :loading="reindexing" @click="reindex">重建全部</el-button>
              <el-button v-hasPermi="['ai:knowledge:upload']" type="primary" :icon="Upload" :loading="uploading" @click="fileInput?.click()">上传文件</el-button>
              <input ref="fileInput" type="file" multiple hidden @change="uploadFiles" />
            </div>
            <div class="tab-scroll">
              <el-empty v-if="!documents.length" :image-size="100" description="添加一份资料，开始积累知识">
                <el-button v-hasPermi="['ai:knowledge:upload']" type="primary" @click="fileInput?.click()">上传文件</el-button>
              </el-empty>
              <div v-for="document in filteredDocuments" :key="document.fileId" class="document-row">
                <span class="document-icon"><el-icon><Document /></el-icon></span>
                <button class="document-copy" @click="openDocument(document)">
                  <strong>{{ document.name }}</strong>
                  <small>{{ formatSize(document.size) }}<span v-if="document.indexedChunks"> · {{ document.indexedChunks }} 个片段</span><span v-if="document.durationMs"> · {{ formatDuration(document.durationMs) }}</span></small>
                  <el-progress v-if="isActive(document)" :percentage="document.progress || 0" :stroke-width="4" :show-text="false" />
                </button>
                <el-tooltip :content="document.indexMessage || statusLabel(document)" placement="top">
                  <span class="status-pill" :class="statusClass(document)">{{ statusLabel(document) }}</span>
                </el-tooltip>
                <el-button v-if="isActive(document) && checkPermi(['ai:knowledge:index'])" text @click="pauseDocument(document)">暂停</el-button>
                <el-button v-if="!isActive(document) && checkPermi(['ai:knowledge:index'])" text @click="reindexDocument(document)">{{ document.indexStatus === 'PAUSED' ? '恢复' : '重建' }}</el-button>
                <el-button v-hasPermi="['ai:knowledge:remove']" text type="danger" :icon="Delete" aria-label="删除文件" @click="deleteDocument(document)" />
              </div>
              <p class="format-note">支持 PDF、Word、Excel、PPTX、Markdown 和代码文件。单文件不超过 20 MB。</p>
            </div>
          </el-tab-pane>
          <el-tab-pane v-if="checkPermi(['ai:knowledge:query'])" label="检索测试" name="search">
            <form class="retrieval-form" @submit.prevent="search">
              <el-input v-model="searchQuery" clearable placeholder="输入问题、模型名或错误信息" :prefix-icon="Search" />
              <el-button native-type="submit" type="primary" :loading="searching" :disabled="!selectedBase.enabled">检索</el-button>
            </form>
            <div v-if="searchTrace" class="search-trace">
              <strong>{{ searchTrace.mode === 'keyword' ? '关键词召回' : '混合检索 · RRF' }}</strong>
              <span>关键词 {{ searchTrace.keywordCandidates }}</span><span>向量 {{ searchTrace.vectorCandidates }}</span>
              <span>返回 {{ searchTrace.returned }}</span><span>{{ searchTrace.elapsedMs }} ms</span>
            </div>
            <el-alert v-for="warning in searchTrace?.warnings || []" :key="warning" :title="warning" type="warning" :closable="false" />
            <div class="tab-scroll result-list">
              <el-empty v-if="searched && !searchResults.length" :image-size="80" description="没有相关片段，试试更具体的问题" />
              <article v-for="(item, index) in searchResults" :key="item.key" class="result-card">
                <div class="result-heading"><strong>{{ index + 1 }}. {{ item.name }}</strong><el-button text @click="openCitation(item)">查看原文</el-button></div>
                <div class="result-meta"><span>{{ item.pageLabel }}</span><span v-if="item.keywordRank">关键词 #{{ item.keywordRank }}</span><span v-if="item.vectorRank">向量 #{{ item.vectorRank }} · {{ Number(item.vectorScore).toFixed(3) }}</span></div>
                <p>{{ item.snippet }}</p>
              </article>
            </div>
          </el-tab-pane>
          <el-tab-pane label="配置" name="settings">
            <div class="tab-scroll settings-scroll">
              <el-form label-position="top" class="settings-form" :disabled="!checkPermi(['ai:knowledge:edit'])">
                <div class="settings-section"><h3>文档分块</h3><span>修改后需重建现有文档索引</span></div>
                <div class="settings-grid">
                  <el-form-item label="分块大小（字符）"><el-input-number v-model="settings.chunkSize" :min="300" :max="8000" :step="100" /></el-form-item>
                  <el-form-item label="重叠长度（字符）"><el-input-number v-model="settings.chunkOverlap" :min="0" :max="Math.floor(settings.chunkSize / 2)" :step="20" /></el-form-item>
                </div>
                <div class="settings-section"><h3>检索</h3><span>保存后立即生效</span></div>
                <div class="settings-grid">
                  <el-form-item label="返回片段数 Top-K"><el-input-number v-model="settings.topK" :min="1" :max="20" /></el-form-item>
                  <el-form-item label="向量相似度阈值"><el-input-number v-model="settings.similarityThreshold" :min="0" :max="1" :step="0.05" :precision="2" /></el-form-item>
                  <el-form-item label="精确匹配重排"><el-switch v-model="settings.rerankEnabled" /><small class="field-note">优先保留完整名称、错误码和原句匹配</small></el-form-item>
                </div>
                <div class="settings-section"><h3>向量模型</h3><span>与聊天模型分别配置</span></div>
                <div class="settings-grid">
                  <el-form-item label="向量服务"><el-select v-model="settings.embeddingProvider">
                    <el-option label="跟随系统配置" value="global" /><el-option label="本地词法向量（基础）" value="local" />
                    <el-option label="Ollama" value="ollama" /><el-option label="OpenAI 兼容接口" value="openai" />
                  </el-select></el-form-item>
                  <el-form-item v-if="remoteEmbedding" label="模型名称"><el-input v-model="settings.embeddingModel" placeholder="填写已部署的向量模型名称" /></el-form-item>
                  <el-form-item v-if="remoteEmbedding" label="服务地址"><el-input v-model="settings.embeddingBaseUrl" :placeholder="settings.embeddingProvider === 'ollama' ? 'http://localhost:11434' : 'https://服务地址/v1'" /></el-form-item>
                  <el-form-item v-if="remoteEmbedding" label="API Key"><el-input v-model="settings.embeddingApiKey" type="password" show-password :placeholder="settings.apiKeyConfigured ? '已保存；留空保留原密钥' : '本地服务可不填'" autocomplete="new-password" /></el-form-item>
                </div>
              </el-form>
            </div>
            <div v-if="checkPermi(['ai:knowledge:edit'])" class="settings-footer"><el-button type="primary" :loading="savingSettings" @click="saveSettings()">保存配置</el-button><el-button v-hasPermi="['ai:knowledge:index']" :loading="savingSettings" @click="saveSettingsAndReindex">保存并重建</el-button><span v-if="settingsDirty">有未保存的修改</span></div>
          </el-tab-pane>
        </el-tabs>
      </main>
      <main v-else class="empty-library"><el-icon><Collection /></el-icon><h2>给资料一个专属空间</h2><el-button v-hasPermi="['ai:knowledge:add']" type="primary" @click="openCreate">创建知识库</el-button></main>
    </div>
    <el-dialog v-model="dialog.open" :title="dialog.editing ? '编辑知识库' : '新建知识库'" width="min(460px, 92vw)">
      <el-form label-position="top" :disabled="dialog.editing && !checkPermi(['ai:knowledge:edit'])">
        <el-form-item label="名称"><el-input v-model="dialog.form.name" maxlength="128" placeholder="例如：产品文档" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="dialog.form.description" type="textarea" :rows="3" maxlength="500" /></el-form-item>
      </el-form>
      <template #footer><el-button v-if="dialog.editing" v-hasPermi="['ai:knowledge:remove']" type="danger" text @click="deleteBase">删除知识库</el-button><el-button @click="dialog.open = false">取消</el-button><el-button v-if="checkPermi([dialog.editing ? 'ai:knowledge:edit' : 'ai:knowledge:add'])" type="primary" :loading="dialog.saving" @click="saveBase">保存</el-button></template>
    </el-dialog>
    <el-drawer v-model="detail.open" :title="detail.name" size="min(620px, 95vw)">
      <template v-if="detail.document">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="状态">{{ statusLabel(detail.document) }}</el-descriptions-item>
          <el-descriptions-item label="当前阶段">{{ detail.document.stage || '—' }}</el-descriptions-item>
          <el-descriptions-item label="进度">{{ detail.document.progress || 0 }}%</el-descriptions-item>
          <el-descriptions-item label="执行次数">{{ detail.document.attempts || 0 }} / 3</el-descriptions-item>
          <el-descriptions-item label="开始时间">{{ formatDate(detail.document.startedAt) }}</el-descriptions-item>
          <el-descriptions-item label="完成时间">{{ formatDate(detail.document.finishedAt) }}</el-descriptions-item>
          <el-descriptions-item label="任务">{{ detail.document.indexTaskId || '—' }}</el-descriptions-item>
          <el-descriptions-item label="说明">{{ detail.document.indexMessage || '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-button v-hasPermi="['ai:knowledge:index']" class="detail-retry" :disabled="isActive(detail.document)" @click="reindexDocument(detail.document)">重新建立索引</el-button>
      </template>
      <el-skeleton v-if="detail.loading" :rows="6" animated />
      <el-alert v-if="detail.error" :title="detail.error" type="warning" :closable="false" />
      <pre v-if="detail.content" class="source-preview">{{ detail.content }}</pre>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Collection, Document, Plus, Search, Upload, Refresh, EditPen, Delete } from '@element-plus/icons-vue'
import { getToken } from '@/utils/auth'
import { checkPermi } from '@/utils/permission'

const bases = ref([])
const selectedBaseId = ref('')
const documents = ref([])
const baseFilter = ref('')
const documentFilter = ref('')
const activeTab = ref('documents')
const fileInput = ref(null)
const loading = ref(false)
const uploading = ref(false)
const reindexing = ref(false)
const searching = ref(false)
const savingSettings = ref(false)
const searchQuery = ref('')
const searchResults = ref([])
const searchTrace = ref(null)
const searched = ref(false)
const settings = reactive({})
const savedSettings = ref('')
const detail = reactive({ open: false, loading: false, name: '', content: '', error: '', document: null })
const dialog = reactive({ open: false, editing: false, saving: false, id: '', form: { name: '', description: '' } })
const selectedBase = computed(() => bases.value.find(item => item.id === selectedBaseId.value))
const filteredBases = computed(() => bases.value.filter(item => item.name.toLowerCase().includes(baseFilter.value.toLowerCase())))
const filteredDocuments = computed(() => documents.value.filter(item => item.name.toLowerCase().includes(documentFilter.value.toLowerCase())))
const remoteEmbedding = computed(() => ['openai', 'ollama'].includes(settings.embeddingProvider))
const settingsDirty = computed(() => JSON.stringify(settings) !== savedSettings.value)
const counts = computed(() => ({
  ready: documents.value.filter(item => item.indexStatus === 'SUCCEEDED' && !item.needsReindex).length,
  active: documents.value.filter(isActive).length,
  failed: documents.value.filter(item => item.indexStatus === 'FAILED').length,
  stale: documents.value.filter(item => item.needsReindex).length
}))
let pollTimer
let unmounted = false
let documentRequest = 0
const baseUrl = id => '/system/ai/knowledge-bases/' + encodeURIComponent(id)

async function request(url, options = {}) {
  const token = getToken()
  const response = await fetch((import.meta.env.VITE_APP_BASE_API || '') + url, {
    ...options, headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(options.headers || {}) }
  })
  const payload = await response.json()
  if (!response.ok || payload.code !== 200) throw new Error(payload.msg || '请求失败')
  return payload.data
}
const jsonOptions = (method, body) => ({ method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
async function loadBases(preferred) {
  bases.value = await request('/system/ai/knowledge-bases') || []
  const candidate = preferred || selectedBaseId.value || localStorage.getItem('zzl-ai-knowledge-base')
  selectedBaseId.value = bases.value.some(item => item.id === candidate) ? candidate : (bases.value[0]?.id || '')
}
function applySettings() {
  Object.keys(settings).forEach(key => delete settings[key])
  Object.assign(settings, { chunkSize: 1200, chunkOverlap: 180, topK: 6, similarityThreshold: 0.1,
    rerankEnabled: true, embeddingProvider: 'global', embeddingModel: 'global', embeddingBaseUrl: '',
    ...(selectedBase.value?.settings || {}), embeddingApiKey: '' })
  savedSettings.value = JSON.stringify(settings)
}
async function loadDocuments() {
  clearTimeout(pollTimer)
  const id = selectedBaseId.value
  const sequence = ++documentRequest
  if (!id) { documents.value = []; return }
  try {
    const result = await request(baseUrl(id) + '/documents')
    if (unmounted || id !== selectedBaseId.value || sequence !== documentRequest) return
    documents.value = result || []
    if (detail.document) detail.document = documents.value.find(item => item.fileId === detail.document.fileId) || detail.document
    if (documents.value.some(isActive)) pollTimer = setTimeout(loadDocuments, 1800)
  } catch (error) { if (!unmounted && id === selectedBaseId.value) ElMessage.error(error.message) }
}
function selectBase(id) {
  if (id === selectedBaseId.value) return
  selectedBaseId.value = id
}
function openCreate() { Object.assign(dialog, { open: true, editing: false, id: '', form: { name: '', description: '' } }) }
function openEdit(base) { Object.assign(dialog, { open: true, editing: true, id: base.id, form: { name: base.name, description: base.description } }) }
async function saveBase() {
  if (!dialog.form.name.trim()) return ElMessage.warning('请输入名称')
  dialog.saving = true
  try {
    const value = await request(dialog.editing ? baseUrl(dialog.id) : '/system/ai/knowledge-bases', jsonOptions(dialog.editing ? 'PUT' : 'POST', dialog.form))
    dialog.open = false
    await loadBases(value.id)
    ElMessage.success('已保存')
  } catch (error) { ElMessage.error(error.message) } finally { dialog.saving = false }
}
async function deleteBase() {
  try {
    await ElMessageBox.confirm('删除“' + dialog.form.name + '”及其中全部文件？', '删除知识库', { type: 'warning' })
    await request(baseUrl(dialog.id), { method: 'DELETE' })
    dialog.open = false
    await loadBases()
  } catch (error) { if (!['cancel', 'close'].includes(error)) ElMessage.error(error.message) }
}
async function toggleBase(enabled) {
  try {
    await request(baseUrl(selectedBaseId.value), jsonOptions('PUT', { enabled }))
    selectedBase.value.enabled = enabled
  } catch (error) { ElMessage.error(error.message) }
}
async function uploadFiles(event) {
  const files = Array.from(event.target.files || [])
  const id = selectedBaseId.value
  event.target.value = ''
  uploading.value = true
  let count = 0
  try {
    for (const file of files) {
      if (file.size > 20 * 1024 * 1024) { ElMessage.warning(file.name + ' 超过 20 MB'); continue }
      const form = new FormData()
      form.append('file', file)
      try { await request(baseUrl(id) + '/documents', { method: 'POST', body: form }); count++ }
      catch (error) { ElMessage.error(file.name + '：' + error.message) }
    }
    if (count) ElMessage.success('已添加 ' + count + ' 个文件')
    await loadBases()
    await loadDocuments()
  } finally { uploading.value = false }
}
async function deleteDocument(document) {
  const id = selectedBaseId.value
  try {
    await ElMessageBox.confirm('删除“' + document.name + '”？', '删除文件', { type: 'warning' })
    await request(baseUrl(id) + '/documents/' + encodeURIComponent(document.fileId), { method: 'DELETE' })
    await loadBases()
    await loadDocuments()
  } catch (error) { if (!['cancel', 'close'].includes(error)) ElMessage.error(error.message) }
}
async function reindex() {
  reindexing.value = true
  try { await request(baseUrl(selectedBaseId.value) + '/reindex', { method: 'POST' }); await loadDocuments(); ElMessage.success('已提交索引任务') }
  catch (error) { ElMessage.error(error.message) } finally { reindexing.value = false }
}
async function documentAction(document, action) {
  try {
    await request(baseUrl(selectedBaseId.value) + '/documents/' + encodeURIComponent(document.fileId) + '/' + action, { method: 'POST' })
    await loadDocuments()
  } catch (error) { ElMessage.error(error.message) }
}
const reindexDocument = document => documentAction(document, 'reindex')
const pauseDocument = document => documentAction(document, 'pause')
async function saveSettings(rebuild = false) {
  savingSettings.value = true
  try {
    const data = { ...settings }
    if (!data.embeddingApiKey) delete data.embeddingApiKey
    const result = await request(baseUrl(selectedBaseId.value) + '/settings', jsonOptions('PUT', data))
    selectedBase.value.settings = result
    applySettings()
    if (rebuild) await reindex()
    else { await loadDocuments(); ElMessage.success(counts.value.stale ? '已保存，现有文档需要重建索引' : '配置已保存') }
  } catch (error) { ElMessage.error(error.message) } finally { savingSettings.value = false }
}
function saveSettingsAndReindex() { return saveSettings(true) }
async function search() {
  if (!searchQuery.value.trim()) return
  const id = selectedBaseId.value
  searching.value = true
  try {
    const data = await request(baseUrl(id) + '/search/detail?query=' + encodeURIComponent(searchQuery.value.trim()))
    if (id !== selectedBaseId.value) return
    searchResults.value = data.items || []
    searchTrace.value = data.trace
    searched.value = true
  } catch (error) { ElMessage.error(error.message) } finally { searching.value = false }
}
function openDocument(document) { Object.assign(detail, { open: true, loading: false, name: document.name, document, content: '', error: '' }) }
async function openCitation(citation) {
  Object.assign(detail, { open: true, loading: true, name: citation.name, document: null, content: '', error: '' })
  try {
    const data = await request('/system/ai/workspace/files/' + encodeURIComponent(citation.fileId) + '/content')
    const text = data.content || ''
    detail.content = text.slice(Math.max(0, Number(citation.startOffset) - 180), Number(citation.endOffset) + 180) || citation.snippet
  } catch (error) { detail.content = citation.snippet; detail.error = error.message } finally { detail.loading = false }
}
const isActive = document => ['QUEUED', 'RUNNING'].includes(document.indexStatus)
function statusLabel(document) {
  if (document.needsReindex && !isActive(document)) return '待重建'
  return ({ QUEUED: '排队中', RUNNING: '索引中 ' + (document.progress || 0) + '%', PAUSED: '已暂停', SUCCEEDED: '已就绪', FAILED: '失败', UNSUPPORTED: '无可索引文本', NOT_INDEXED: '未索引' })[document.indexStatus] || '未索引'
}
function statusClass(document) { return document.needsReindex ? 'stale' : String(document.indexStatus || '').toLowerCase() }
function embeddingLabel(value) { return !value || value.embeddingProvider === 'global' ? '系统向量模型' : value.embeddingProvider === 'local' ? '本地词法向量' : value.embeddingModel }
function formatSize(value) { return value < 1024 ? value + ' B' : value < 1048576 ? (value / 1024).toFixed(1) + ' KB' : (value / 1048576).toFixed(1) + ' MB' }
function formatDuration(value) { return (Math.max(0, value) / 1000).toFixed(1) + ' 秒' }
function formatDate(value) { return value ? new Date(value).toLocaleString() : '—' }
watch(selectedBaseId, id => {
  if (id) localStorage.setItem('zzl-ai-knowledge-base', id)
  searchResults.value = []; searchTrace.value = null; searched.value = false; searchQuery.value = ''
  detail.open = false
  applySettings()
  loadDocuments()
})
onMounted(async () => {
  loading.value = true
  try { await loadBases(); applySettings() } catch (error) { ElMessage.error(error.message) } finally { loading.value = false }
})
onBeforeUnmount(() => { unmounted = true; documentRequest++; clearTimeout(pollTimer) })
</script>

<style scoped>
.knowledge-page { --ink: #30445a; --muted: #768b99; --line: #e5eeeb; height: calc(100vh - 84px); min-height: 540px; padding: 20px 24px; background: #f4faf7; color: var(--ink); font-family: "Microsoft YaHei", "PingFang SC", sans-serif; display: flex; flex-direction: column; }
.page-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18px; }
.page-heading > div { display: flex; align-items: baseline; gap: 16px; }
h1 { margin: 0; font-size: 26px; font-weight: 700; letter-spacing: -.03em; }
.page-heading span { color: var(--muted); font-size: 13px; }
.knowledge-layout { display: grid; grid-template-columns: 238px minmax(0, 1fr); gap: 20px; flex: 1; min-height: 0; }
.library-list { min-width: 0; min-height: 0; display: flex; flex-direction: column; gap: 16px; padding: 16px 12px; border: 1px solid var(--line); border-radius: 16px; background: #eaf5ef; }
.library-scroll { min-width: 0; min-height: 0; overflow: auto; }
.library-item { display: flex; align-items: center; width: 100%; gap: 12px; padding: 13px 10px; margin-bottom: 6px; border: 1px solid transparent; border-radius: 10px; background: transparent; color: inherit; text-align: left; cursor: pointer; }
.library-item.active { border-color: #addbc8; background: #fff; box-shadow: 0 4px 10px #3a6e5b09; }
.library-item:hover { background: #ffffffad; }
.library-icon { display: grid; flex: 0 0 34px; height: 38px; place-items: center; color: #289a77; background: #d4efe3; border-radius: 6px 11px 11px 6px; font-size: 19px; }
.library-item > span:last-child { min-width: 0; display: grid; gap: 7px; }
.library-item strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 14px; }
.library-item small { color: var(--muted); font-size: 11px; }
.library-content { min-width: 0; min-height: 0; padding: 22px 26px 0; background: white; border: 1px solid var(--line); border-radius: 16px; display: flex; flex-direction: column; overflow: hidden; }
.library-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 14px; }
h2 { font-size: 20px; margin: 0; }
.library-heading p { color: var(--muted); margin: 9px 0 0; font-size: 12px; }
.library-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 15px; padding: 12px 0; color: var(--muted); font-size: 12px; }
.dot { display: inline-block; width: 7px; height: 7px; margin-right: 6px; border-radius: 50%; }
.mint { background: #37b38d; }.amber { background: #e8b558; }.coral { background: #e48075; }.violet { background: #9781d9; }
.summary-model { margin-left: auto; max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.knowledge-tabs { display: flex; flex: 1; flex-direction: column; min-height: 0; }
.knowledge-tabs :deep(.el-tabs__content) { flex: 1; min-height: 0; overflow: hidden; }
.knowledge-tabs :deep(.el-tab-pane) { height: 100%; min-height: 0; display: flex; flex-direction: column; }
.knowledge-tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; background: var(--line); }
.document-toolbar, .retrieval-form { display: flex; align-items: center; gap: 10px; margin: 4px 0 18px; }
.document-toolbar .el-input { max-width: 290px; margin-right: auto; }
.tab-scroll { flex: 1; min-height: 0; overflow: auto; padding-bottom: 20px; }
.document-row { display: flex; align-items: center; gap: 13px; border-bottom: 1px solid #eef3f1; padding: 15px 0; }
.document-icon { display: grid; place-items: center; width: 36px; height: 42px; border-radius: 7px; background: #f3f1ff; color: #9580cc; font-size: 21px; flex-shrink: 0; }
.document-copy { flex: 1; min-width: 0; display: grid; gap: 8px; padding: 0; color: inherit; text-align: left; border: 0; background: transparent; cursor: pointer; }
.document-copy strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; font-weight: 600; }
.document-copy small { color: var(--muted); font-size: 11px; }
.document-copy .el-progress { max-width: 280px; }
.status-pill { flex-shrink: 0; font-size: 11px; padding: 5px 8px; border-radius: 5px; background: #f0f3f4; color: #7c8e9a; }
.status-pill.succeeded { background: #e6f7ee; color: #25956e; }.status-pill.running, .status-pill.queued { color: #a17a27; background: #fff5de; }.status-pill.failed { color: #c9675f; background: #fff0ec; }.status-pill.stale { color: #8f73bf; background: #f2edff; }
.format-note { color: #8d9f9b; font-size: 11px; margin: 20px 0 0; }
.search-trace { display: flex; gap: 16px; flex-wrap: wrap; padding: 11px 14px; margin-bottom: 12px; background: #eef8f3; border-radius: 7px; font-size: 12px; color: #6c8c7d; }
.search-trace strong { color: #319173; }.result-list { margin-top: 10px; }
.result-card { border-bottom: 1px solid var(--line); padding: 12px 0 16px; }
.result-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; }.result-heading strong { font-size: 13px; }
.result-meta { display: flex; flex-wrap: wrap; gap: 14px; color: #8b9b98; font-size: 11px; }
.result-card p { max-width: 78ch; color: #5e7370; font-size: 13px; line-height: 1.8; white-space: pre-wrap; }
.settings-form { max-width: 780px; }.settings-section { display: flex; align-items: baseline; gap: 14px; margin: 8px 0 16px; border-bottom: 1px solid var(--line); padding-bottom: 10px; }
.settings-section h3 { font-size: 14px; margin: 0; }.settings-section span { font-size: 11px; color: var(--muted); }
.settings-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 28px; }
.settings-grid .el-input-number, .settings-grid .el-select { width: 100%; }
.field-note { margin-left: 12px; color: var(--muted); font-size: 11px; }
.settings-footer { padding: 14px 0; display: flex; align-items: center; gap: 10px; border-top: 1px solid var(--line); background: white; }
.settings-footer span { font-size: 11px; color: #a88a51; }
.source-preview { padding: 18px; background: #f5faf7; border-radius: 8px; color: #45635b; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.8 "Microsoft YaHei", sans-serif; }
.detail-retry { margin: 20px 0; }
.empty-library { display: grid; place-content: center; justify-items: center; gap: 20px; background: white; border-radius: 16px; }.empty-library > .el-icon { font-size: 48px; color: #7ac1a2; }
.library-item:focus-visible, .document-copy:focus-visible { outline: 2px solid #49a98b; outline-offset: 2px; }
@media (max-width: 950px) { .knowledge-page { padding: 14px; }.knowledge-layout { grid-template-columns: 190px minmax(0,1fr); gap: 12px; }.library-content { padding: 18px 16px 0; }.summary-model { display: none; }.settings-grid { gap: 0 14px; } }
@media (max-width: 640px) { .knowledge-page { height: auto; min-height: calc(100vh - 84px); }.page-heading span { display: none; }.knowledge-layout { grid-template-columns: minmax(0, 1fr); }.library-list { max-height: 190px; }.library-scroll { display: flex; gap: 8px; }.library-item { flex: 0 0 180px; }.library-content { height: 640px; }.settings-grid { grid-template-columns: minmax(0, 1fr); }.document-toolbar { flex-wrap: wrap; }.document-toolbar .el-input { max-width: none; }.document-row { gap: 7px; }.document-row .el-button { padding: 5px; }.settings-section { gap: 7px; } }
</style>

<template>
  <div class="models-page">
    <section class="page-heading">
      <div>
        <p class="eyebrow">模型配置</p>
        <h1>模型管理</h1>
      </div>
      <el-button v-hasPermi="['ai:model:add']" type="primary" @click="resetForm">＋ 添加模型</el-button>
    </section>

    <div class="models-layout">
      <section class="panel model-list-panel">
        <div class="panel-heading"><strong>当前账号模型</strong><el-button text :loading="loading" @click="loadModels">刷新</el-button></div>
        <div v-if="!models.length" class="empty-state">还没有模型配置，先保存 Ollama 或添加一个第三方模型。</div>
        <button v-for="model in models" :key="model.id" type="button" :class="['model-card', { active: draft.id === model.id }]" @click="editModel(model)">
          <span class="provider-mark">{{ model.label?.slice(0, 1) || 'AI' }}</span>
          <span class="model-card-main"><strong>{{ model.label || model.id }}</strong><small>{{ model.modelName || '未填写模型名' }}</small><small>{{ providerLabel(model.providerType) }} · {{ model.source === 'runtime' ? '当前账号保存' : '系统默认' }}</small></span>
          <el-tag size="small" :type="model.configured ? 'success' : 'info'">{{ model.configured ? '可用' : '未配置' }}</el-tag>
        </button>
      </section>

      <section class="panel editor-panel">
        <div class="panel-heading"><strong>{{ editing ? (canSave ? '编辑模型' : '模型详情') : '添加模型' }}</strong><el-tag v-if="editing" effect="plain">{{ draft.id }}</el-tag></div>
        <el-form label-position="top" class="model-form" :disabled="!canSave">
          <el-form-item label="模型标识"><el-input v-model="draft.id" :disabled="editing || !canSave" placeholder="例如 vendor-prod" /></el-form-item>
          <el-form-item label="显示名称"><el-input v-model="draft.label" placeholder="例如 我的云端模型" /></el-form-item>
          <div class="form-row">
            <el-form-item label="厂商类型">
              <el-select v-model="draft.providerType" class="provider-select">
                <el-option v-for="item in providerOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="模型能力协议">
              <el-select v-model="draft.capabilityProtocol" class="protocol-select">
                <el-option v-for="item in protocolOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
          </div>
          <el-form-item label="Base URL"><el-input v-model="draft.baseUrl" placeholder="https://api.example.com/v1" /></el-form-item>
          <el-form-item label="模型名称">
            <div class="model-picker">
              <el-select
                v-model="draft.modelName"
                class="model-name-select"
                filterable
                allow-create
                default-first-option
                clearable
                placeholder="先获取模型列表，也可以直接输入"
              >
                <el-option v-for="item in modelOptions" :key="item.id" :label="item.label || item.id" :value="item.id" />
              </el-select>
              <el-button v-hasPermi="['ai:model:query']" :loading="fetchingCatalog" :disabled="!draft.baseUrl" @click="fetchModelCatalog">获取模型列表</el-button>
            </div>
          </el-form-item>
          <el-form-item label="API Key"><el-input v-model="draft.apiKey" type="password" show-password placeholder="本地模型可留空" /></el-form-item>
          <div class="form-row"><el-form-item label="启用"><el-switch v-model="draft.enabled" /></el-form-item><el-form-item label="温度"><el-input-number v-model="draft.temperature" :min="0" :max="2" :step="0.1" /></el-form-item><el-form-item label="最大 Token"><el-input-number v-model="draft.maxTokens" :min="64" :max="32000" :step="128" /></el-form-item></div>
          <el-form-item label="推理强度">
            <el-select v-model="draft.reasoningEffort" :disabled="!canSave || reasoningOptions.length <= 1" class="reasoning-select">
              <el-option v-for="item in reasoningOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <small class="reasoning-hint">协议：{{ protocolLabel }}</small>
          </el-form-item>
        </el-form>
        <div class="form-actions">
          <el-button v-if="editing" v-hasPermi="['ai:model:remove']" type="danger" plain @click="removeModel">删除</el-button>
          <span></span>
          <el-button v-hasPermi="['ai:model:query']" :loading="testing" :disabled="!editing" @click="testModel">测试连接</el-button>
          <el-button v-if="canSave" type="primary" :loading="saving" @click="saveModel">保存模型</el-button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getToken } from '@/utils/auth'
import { checkPermi } from '@/utils/permission'

const models = ref([])
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const fetchingCatalog = ref(false)
const editing = ref(false)
const canSave = computed(() => checkPermi([editing.value ? 'ai:model:edit' : 'ai:model:add']))
const modelOptions = ref([])
const providerOptions = [
  { label: '自动识别', value: 'auto' },
  { label: 'OpenAI', value: 'openai' },
  { label: 'Claude', value: 'claude' },
  { label: 'DeepSeek', value: 'deepseek' },
  { label: 'Ollama', value: 'ollama' },
  { label: '其他 / 通用', value: 'custom' }
]
const protocolOptions = [
  { label: '自动选择', value: 'auto' },
  { label: 'OpenAI 推理协议', value: 'openai_reasoning_effort' },
  { label: 'Claude Thinking', value: 'anthropic_thinking' },
  { label: 'DeepSeek 原生', value: 'deepseek_native' },
  { label: 'Ollama 原生', value: 'ollama_native' },
  { label: '通用推理协议', value: 'generic_reasoning_effort' }
]
const draft = reactive({ id: '', label: '', providerType: 'auto', capabilityProtocol: 'auto', baseUrl: '', apiKey: '', modelName: '', modelCatalog: [], reasoningLevels: [], supportsVision: null, supportsTools: null, enabled: true, temperature: 0.2, maxTokens: 1200, reasoningEffort: 'auto' })
const reasoningOptions = computed(() => {
  const explicit = Array.isArray(draft.reasoningLevels) && draft.reasoningLevels.length ? draft.reasoningLevels : defaultReasoningLevels(draft.providerType)
  const levels = [...new Set(['auto', ...explicit])]
  return levels.map(value => ({ value, label: value === 'auto' ? '自动（模型默认）' : value === 'low' ? '低' : value === 'medium' ? '中' : value === 'high' ? '高' : value }))
})
const protocolLabel = computed(() => protocolOptions.find(item => item.value === draft.capabilityProtocol)?.label || '自动选择')
function defaultReasoningLevels(provider) {
  return provider === 'deepseek' || provider === 'ollama' ? ['auto'] : ['auto', 'low', 'medium', 'high']
}
function providerLabel(provider) {
  return providerOptions.find(item => item.value === provider)?.label || '自动识别'
}
const baseUrl = () => import.meta.env.VITE_APP_BASE_API || ''
const headers = () => ({ Authorization: `Bearer ${getToken()}`, 'Content-Type': 'application/json' })

onMounted(loadModels)

async function loadModels() {
  loading.value = true
  try {
    const response = await fetch(`${baseUrl()}/system/ai/models`, { headers: headers() })
    const payload = await response.json()
    if (!response.ok || payload.code !== 200) throw new Error(payload.msg || '模型列表读取失败')
    models.value = Array.isArray(payload.data) ? payload.data : []
    if (!editing.value && !checkPermi(['ai:model:add']) && models.value.length) editModel(models.value[0])
  } catch (error) {
    ElMessage.error(error.message || '模型列表读取失败')
  } finally { loading.value = false }
}

function resetForm() {
  editing.value = false
  modelOptions.value = []
  Object.assign(draft, { id: '', label: '', providerType: 'auto', capabilityProtocol: 'auto', baseUrl: '', apiKey: '', modelName: '', modelCatalog: [], reasoningLevels: [], supportsVision: null, supportsTools: null, enabled: true, temperature: 0.2, maxTokens: 1200, reasoningEffort: 'auto' })
}

function editModel(model) {
  editing.value = true
  const rawCatalog = Array.isArray(model.modelCatalog) && model.modelCatalog.length
    ? model.modelCatalog
    : (model.modelName ? [model.modelName] : [])
  const catalog = rawCatalog.map(item => typeof item === 'string'
    ? { id: item, label: item }
    : { id: item?.id || '', label: item?.label || item?.id || '' }).filter(item => item.id)
  modelOptions.value = catalog
  Object.assign(draft, { id: model.id, label: model.label || '', providerType: model.providerType || 'auto', capabilityProtocol: model.capabilityProtocol || 'auto', baseUrl: model.baseUrl || '', apiKey: model.apiKeyConfigured ? '******' : '', modelName: model.modelName || '', modelCatalog: catalog, reasoningLevels: model.reasoningLevels || model.capabilities?.reasoningLevels || [], supportsVision: model.capabilities?.supportsVision ?? null, supportsTools: model.capabilities?.supportsTools ?? null, enabled: model.enabled !== false, temperature: 0.2, maxTokens: 1200, reasoningEffort: model.reasoningEffort || 'auto' })
}

async function fetchModelCatalog() {
  if (!draft.baseUrl) return ElMessage.warning('请先填写 Base URL')
  fetchingCatalog.value = true
  try {
    const response = await fetch(`${baseUrl()}/system/ai/models/catalog`, {
      method: 'POST',
      headers: headers(),
      body: JSON.stringify({ id: editing.value ? draft.id : '', providerType: draft.providerType, baseUrl: draft.baseUrl, apiKey: draft.apiKey })
    })
    const payload = await response.json()
    if (!response.ok || payload.code !== 200) throw new Error(payload.msg || '获取模型列表失败')
    modelOptions.value = (Array.isArray(payload.data) ? payload.data : [])
      .map(item => typeof item === 'string'
        ? { id: item, label: item }
        : { id: item?.id || '', label: item?.label || item?.id || '' })
      .filter(item => item.id)
    draft.modelCatalog = modelOptions.value
    if (!draft.modelName && modelOptions.value.length) draft.modelName = modelOptions.value[0].id
    if (!modelOptions.value.length) ElMessage.warning('接口没有返回可用模型，请直接输入模型名称')
    else ElMessage.success(`已获取 ${modelOptions.value.length} 个模型`)
  } catch (error) {
    ElMessage.error(error.message || '获取模型列表失败')
  } finally { fetchingCatalog.value = false }
}

async function saveModel() {
  if (!draft.id || !/^[A-Za-z0-9_-]{2,40}$/.test(draft.id)) return ElMessage.warning('模型标识需为 2-40 位字母、数字、下划线或短横线')
  if (!draft.baseUrl || !draft.modelName) return ElMessage.warning('Base URL 和模型名称不能为空')
  saving.value = true
  try {
    const response = await fetch(`${baseUrl()}/system/ai/models${editing.value ? `/${encodeURIComponent(draft.id)}` : ''}`, { method: 'POST', headers: headers(), body: JSON.stringify({ ...draft }) })
    const payload = await response.json()
    if (!response.ok || payload.code !== 200) throw new Error(payload.msg || '保存失败')
    ElMessage.success('模型已保存到当前账号')
    await loadModels()
    editing.value = true
  } catch (error) { ElMessage.error(error.message || '保存失败') } finally { saving.value = false }
}

async function testModel() {
  if (!editing.value) return
  testing.value = true
  try {
    const response = await fetch(`${baseUrl()}/system/ai/models/${encodeURIComponent(draft.id)}/test`, { method: 'POST', headers: headers() })
    const payload = await response.json()
    if (!response.ok || payload.code !== 200) throw new Error(payload.msg || '连接失败')
    ElMessage.success(payload.msg || '连接成功')
  } catch (error) { ElMessage.error(error.message || '连接失败') } finally { testing.value = false }
}

async function removeModel() {
  try {
    await ElMessageBox.confirm(`确定删除模型“${draft.label || draft.id}”吗？`, '删除模型', { type: 'warning' })
    const response = await fetch(`${baseUrl()}/system/ai/models/${encodeURIComponent(draft.id)}`, { method: 'DELETE', headers: headers() })
    const payload = await response.json()
    if (!response.ok || payload.code !== 200) throw new Error(payload.msg || '删除失败')
    ElMessage.success('模型已删除')
    resetForm()
    await loadModels()
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error.message || '删除失败') }
}
</script>

<style scoped lang="scss">
.models-page {
  --ink: #fff9f2;
  --surface: #fffdf9;
  --surface-raised: #ffffff;
  --line: #e9e3ee;
  --text: #2b3044;
  --muted: #7d7e91;
  --teal: #45b99e;
  --amber: #e99d56;
  min-height: calc(100vh - 84px);
  padding: clamp(16px, 2.4vw, 32px);
  color: var(--text);
  background: radial-gradient(circle at 10% 0%, rgba(143, 225, 199, .26), transparent 32%),
    radial-gradient(circle at 90% 0%, rgba(255, 205, 143, .24), transparent 26%), var(--ink);
  font-family: "Avenir Next", "Noto Sans SC", "PingFang SC", "Segoe UI", sans-serif;
}
.page-heading { position: relative; display: flex; align-items: flex-end; justify-content: space-between; gap: 18px; margin-bottom: 22px; padding-bottom: 20px; }
.page-heading::after { position: absolute; right: 0; bottom: 0; left: 0; height: 1px; content: ''; background: linear-gradient(90deg, var(--teal), rgba(130, 227, 213, .12), transparent); }
.page-heading h1 { margin: 0 0 7px; color: var(--text); font-size: clamp(26px, 3vw, 36px); font-weight: 650; letter-spacing: -.035em; line-height: 1.12; }
.page-heading p:not(.eyebrow) { max-width: 640px; margin: 0; color: var(--muted); font-size: 13px; line-height: 1.65; }
.eyebrow { margin: 0 0 8px; color: var(--teal); font-size: 11px; font-weight: 700; letter-spacing: .08em; }
.models-layout { display: grid; grid-template-columns: minmax(260px, .75fr) minmax(420px, 1.25fr); align-items: start; gap: 14px; }
.panel { padding: 18px; border: 1px solid var(--line); border-radius: 12px; background: rgba(255, 253, 249, .94); box-shadow: 0 20px 50px rgba(77, 65, 99, .10); }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 14px; }
.panel-heading strong { color: var(--text); font-size: 13px; font-weight: 650; }
.model-card { display: flex; align-items: center; width: 100%; gap: 10px; padding: 13px 10px; border: 1px solid transparent; border-radius: 8px; color: var(--muted); text-align: left; background: transparent; cursor: pointer; }
.model-card:hover, .model-card.active { border-color: rgba(130, 227, 213, .24); color: var(--text); background: rgba(130, 227, 213, .08); }
.model-card.active { box-shadow: inset 2px 0 var(--teal); }
.provider-mark { display: grid; place-items: center; width: 34px; height: 34px; flex: 0 0 auto; border: 1px solid rgba(69, 185, 158, .34); border-radius: 9px; color: var(--teal); font-weight: 800; background: #e8faf2; }
.model-card-main { display: grid; min-width: 0; flex: 1; gap: 3px; }
.model-card-main strong, .model-card-main small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.model-card-main strong { color: inherit; font-size: 12px; font-weight: 550; }
.model-card-main small { color: #6f8f95; font-size: 10px; }
.empty-state { padding: 40px 10px; color: #6f8f95; font-size: 12px; text-align: center; }
.model-form { padding-top: 4px; }
.model-form :deep(.el-form-item) { margin-bottom: 16px; }
.model-form :deep(.el-form-item__label) { color: #6f7183; font-size: 11px; }
.model-picker { display: flex; align-items: center; gap: 8px; width: 100%; }
.model-name-select { min-width: 0; flex: 1; }
.provider-select, .protocol-select { width: 100%; }
.reasoning-select { width: 100%; }
.reasoning-hint { display: block; margin-top: 5px; color: var(--muted); font-size: 11px; line-height: 1.4; }
.form-row { display: grid; grid-template-columns: 1fr 1fr 1.25fr; gap: 14px; }
.form-actions { display: grid; grid-template-columns: auto 1fr auto auto; gap: 9px; align-items: center; padding-top: 6px; }
.provider-hint { display: grid; gap: 5px; margin-top: 22px; padding: 12px; border: 1px solid rgba(130, 227, 213, .16); border-radius: 8px; color: var(--muted); font-size: 11px; line-height: 1.6; background: rgba(130, 227, 213, .06); }
.provider-hint strong { color: var(--teal); font-size: 10px; }
.models-page :deep(.el-input__wrapper), .models-page :deep(.el-input-number), .models-page :deep(.el-input-number .el-input__wrapper) { border: 1px solid var(--line); border-radius: 8px; background: #ffffff; box-shadow: none; }
.models-page :deep(.el-input__inner) { color: var(--text); }
.models-page :deep(.el-input__inner::placeholder) { color: #66858b; }
.models-page :deep(.el-button) { --el-button-text-color: var(--muted); --el-button-hover-text-color: var(--teal); --el-button-hover-bg-color: rgba(130, 227, 213, .08); }
.models-page :deep(.el-button--primary) { --el-button-bg-color: var(--teal); --el-button-border-color: var(--teal); --el-button-hover-bg-color: #a1f0e5; --el-button-hover-border-color: #a1f0e5; --el-button-text-color: var(--ink); }
.models-page :deep(.el-tag) { border-color: rgba(130, 227, 213, .26); color: var(--teal); background: rgba(130, 227, 213, .08); }
@media (max-width: 900px) { .models-layout { grid-template-columns: 1fr; } }
@media (max-width: 560px) { .models-page { padding: 14px; } .page-heading { align-items: flex-start; flex-direction: column; } .form-row { grid-template-columns: 1fr; gap: 0; } .form-actions { grid-template-columns: 1fr 1fr; } .form-actions span { display: none; } }
</style>

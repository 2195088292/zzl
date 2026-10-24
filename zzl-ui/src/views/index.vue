<template>
  <div class="home-page">
    <section class="welcome-band">
      <div class="welcome-copy">
        <p class="eyebrow">管理工作台</p>
        <h1>今天也把系统打理得漂漂亮亮</h1>
        <p>这里不是冷冰冰的数据面板，而是你的管理游乐场。用户、权限、任务、代码生成，都从一个轻快的首页开始。</p>
        <div class="welcome-actions">
          <el-button type="primary" icon="User" @click="goTarget('/system/user')">管理用户</el-button>
          <el-button icon="MagicStick" @click="goTarget('/tool/gen')">生成代码</el-button>
        </div>
      </div>

      <div class="desk-scene" aria-hidden="true">
        <div class="helper helper-purple">
          <span class="eye left"></span>
          <span class="eye right"></span>
        </div>
        <div class="helper helper-black">
          <span class="eye left"></span>
          <span class="eye right"></span>
        </div>
        <div class="helper helper-yellow">
          <span class="eye left"></span>
          <span class="eye right"></span>
          <span class="mouth"></span>
        </div>
        <div class="helper helper-orange">
          <span class="eye left"></span>
          <span class="eye right"></span>
        </div>
        <div class="paper paper-one">Vue3</div>
        <div class="paper paper-two">Pinia</div>
      </div>
    </section>

    <section class="dashboard-toolbar" aria-live="polite">
      <div>
        <el-tag :type="dashboardError ? 'danger' : loading ? 'warning' : 'success'" effect="plain">
          {{ dashboardError ? '数据读取失败' : loading ? '正在同步' : streamConnected ? '实时数据' : '轮询数据' }}
        </el-tag>
        <small v-if="updatedAt">更新于 {{ formatTime(updatedAt) }}</small>
      </div>
      <el-button text icon="Refresh" :loading="loading" @click="loadDashboard">刷新数据</el-button>
    </section>

    <div v-if="loading" class="dashboard-state" role="status">
      <span class="state-spinner"></span>
      正在读取工作台数据…
    </div>

    <div v-else-if="dashboardError" class="dashboard-state dashboard-error" role="alert">
      <strong>首页数据暂时无法读取</strong>
      <span>{{ dashboardError }}</span>
      <el-button type="primary" plain @click="loadDashboard">重新加载</el-button>
    </div>

    <template v-else>
    <section class="bubble-grid">
      <article v-for="item in metrics" :key="item.key || item.label" class="bubble-card" :class="item.tone">
        <el-icon><component :is="item.icon" /></el-icon>
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
        <small>{{ item.trend }}</small>
      </article>
    </section>

    <section class="play-grid">
      <div class="panel panel-wide">
        <div class="panel-header">
          <div>
            <p class="eyebrow">服务看板</p>
            <h2>服务状态</h2>
          </div>
          <el-tag type="success" effect="plain">{{ services.length ? '已连接' : '暂无数据' }}</el-tag>
        </div>
        <div class="health-map">
          <div v-for="service in services" :key="service.key || service.name" class="health-item" :class="service.tone">
            <div class="health-top">
              <span>{{ service.name }}</span>
              <strong>{{ service.status }} · {{ service.percent }}%</strong>
            </div>
            <div class="health-track">
              <i :style="{ width: service.percent + '%' }"></i>
            </div>
            <small>{{ service.detail }}</small>
          </div>
          <div v-if="!services.length" class="inline-empty">暂无服务数据</div>
        </div>
      </div>

      <div class="panel panel-wide service-trend-panel">
        <div class="panel-header">
          <div>
            <p class="eyebrow">实时趋势</p>
            <h2>服务资源趋势</h2>
          </div>
          <small class="trend-caption">最近 12 个采样点</small>
        </div>
        <div ref="serviceChart" class="service-chart" role="img" aria-label="服务状态趋势图"></div>
      </div>

      <div class="panel sticker-panel">
        <div class="panel-header">
          <div>
            <p class="eyebrow">常用入口</p>
            <h2>快捷贴纸</h2>
          </div>
        </div>
        <div class="sticker-grid">
          <button v-for="action in shortcuts" :key="action.label" type="button" @click="goTarget(action.path)">
            <el-icon><component :is="action.icon" /></el-icon>
            <span>{{ action.label }}</span>
          </button>
        </div>
      </div>

      <div class="panel">
        <div class="panel-header">
          <div>
            <p class="eyebrow">今日节奏</p>
            <h2>今日小任务</h2>
          </div>
          <span class="task-count">{{ todoCount }}</span>
        </div>
        <ul class="task-list">
          <li v-for="task in tasks" :key="task.key || task.title">
            <span :class="['task-dot', task.level]"></span>
            <div>
              <strong>{{ task.title }}</strong>
              <small>{{ task.desc }}</small>
            </div>
          </li>
          <li v-if="!tasks.length" class="empty-task">暂无待办，当前很清爽</li>
        </ul>
      </div>

      <div class="panel panel-wide activity-panel">
        <div class="panel-header">
          <div>
            <p class="eyebrow">活动时间</p>
            <h2>最近发生了什么</h2>
          </div>
          <el-button text icon="Refresh" :loading="loading" @click="loadDashboard">刷新</el-button>
        </div>
        <div class="activity-list">
          <div v-for="item in activities" :key="item.id || item.time" class="activity-item">
            <span>{{ item.time }}</span>
            <strong>{{ item.title }}</strong>
            <small>{{ item.detail }}</small>
          </div>
          <div v-if="!activities.length" class="inline-empty">暂无最近活动</div>
        </div>
      </div>
    </section>
    </template>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { getDashboardOverview, openDashboardStream } from '@/api/dashboard'

const router = useRouter()

const metrics = ref([])
const services = ref([])
const tasks = ref([])
const activities = ref([])
const todoCount = ref(0)
const updatedAt = ref('')
const loading = ref(false)
const dashboardError = ref('')
const streamConnected = ref(false)
const serviceChart = ref(null)
const serviceHistory = ref([])
let chartInstance
let streamClient
let streamRetryTimer
let refreshTimer

const shortcuts = [
  { label: '用户管理', path: '/system/user', icon: 'User' },
  { label: '角色权限', path: '/system/role', icon: 'Lock' },
  { label: '菜单配置', path: '/system/menu', icon: 'Menu' },
  { label: '代码生成', path: '/tool/gen', icon: 'MagicStick' }
]

async function loadDashboard() {
  loading.value = true
  dashboardError.value = ''
  try {
    const response = await getDashboardOverview()
    const data = response?.data || {}
    await applyDashboardData(data)
  } catch (error) {
    dashboardError.value = '接口连接失败，请稍后重试。'
    metrics.value = []
    services.value = []
    tasks.value = []
    activities.value = []
    todoCount.value = 0
    updatedAt.value = ''
  } finally {
    loading.value = false
  }
}

async function applyDashboardData(data) {
  metrics.value = Array.isArray(data.metrics) ? data.metrics : []
  services.value = Array.isArray(data.services || data.serviceHealth) ? (data.services || data.serviceHealth) : []
  tasks.value = Array.isArray(data.tasks || data.todo) ? (data.tasks || data.todo) : []
  activities.value = Array.isArray(data.activities) ? data.activities : []
  todoCount.value = Number.isFinite(Number(data.todoCount)) ? Number(data.todoCount) : tasks.value.length
  updatedAt.value = data.updatedAt || ''
  await nextTick()
  recordServiceSnapshot(services.value, updatedAt.value)
}

function recordServiceSnapshot(snapshot, timestamp) {
  if (!snapshot.length) return
  const point = {
    label: formatTime(timestamp || new Date().toISOString()),
    values: Object.fromEntries(snapshot.map(item => [item.key || item.name, Number(item.percent) || 0])),
    names: Object.fromEntries(snapshot.map(item => [item.key || item.name, item.name]))
  }
  serviceHistory.value = [...serviceHistory.value, point].slice(-12)
  renderServiceChart()
}

function renderServiceChart() {
  if (!serviceChart.value || !serviceHistory.value.length) return
  chartInstance ||= echarts.init(serviceChart.value)
  const keys = Object.keys(serviceHistory.value.at(-1).values)
  chartInstance.setOption({
    color: ['#6d4df7', '#3fa7ff', '#f1c453', '#ef8c5b'],
    tooltip: { trigger: 'axis' },
    legend: { top: 0, right: 0, textStyle: { color: '#7d7488' } },
    grid: { top: 38, right: 12, bottom: 24, left: 38 },
    xAxis: { type: 'category', boundaryGap: false, data: serviceHistory.value.map(item => item.label), axisLine: { lineStyle: { color: '#ddd6eb' } } },
    yAxis: { type: 'value', min: 0, max: 100, splitNumber: 4, axisLabel: { formatter: '{value}%' }, splitLine: { lineStyle: { color: '#eeeaf5' } } },
    series: keys.map(key => ({
      name: serviceHistory.value.at(-1).names[key] || key,
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: serviceHistory.value.map(item => item.values[key] ?? null)
    }))
  }, true)
}

function connectDashboardStream() {
  streamClient?.close()
  streamClient = openDashboardStream({
    onOpen: () => {
      streamConnected.value = true
    },
    onMessage: data => {
      applyDashboardData(data).catch(() => {})
    },
    onError: () => {
      streamConnected.value = false
      if (!streamRetryTimer) {
        streamRetryTimer = window.setTimeout(() => {
          streamRetryTimer = null
          connectDashboardStream()
        }, 15000)
      }
    }
  })
}

function handleChartResize() {
  chartInstance?.resize()
}

function formatTime(value) {
  const time = new Date(value)
  return Number.isNaN(time.getTime()) ? value : time.toLocaleString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

function handleVisibilityChange() {
  if (document.visibilityState === 'visible') loadDashboard()
}

onMounted(() => {
  loadDashboard()
  connectDashboardStream()
  document.addEventListener('visibilitychange', handleVisibilityChange)
  refreshTimer = window.setInterval(loadDashboard, 60000)
  window.addEventListener('resize', handleChartResize)
})

onBeforeUnmount(() => {
  streamClient?.close()
  window.clearTimeout(streamRetryTimer)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  window.clearInterval(refreshTimer)
  window.removeEventListener('resize', handleChartResize)
  chartInstance?.dispose()
})

function goTarget(path) {
  router.push(path)
}
</script>

<style lang="scss" scoped>
.home-page {
  min-height: calc(100vh - 84px);
  padding: 22px;
  color: #201b2d;
  background:
    linear-gradient(90deg, rgba(255, 255, 255, 0.55) 1px, transparent 1px),
    linear-gradient(0deg, rgba(255, 255, 255, 0.48) 1px, transparent 1px),
    #f8f4ff;
  background-size: 38px 38px;
}

.welcome-band {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(330px, 460px);
  gap: 28px;
  align-items: stretch;
  min-height: 260px;
  padding: 30px;
  border: 1px solid rgba(108, 84, 180, 0.16);
  border-radius: 8px;
  background: #fffdf8;
  box-shadow: 0 18px 46px rgba(76, 59, 112, 0.12);
}

.welcome-copy {
  display: flex;
  flex-direction: column;
  justify-content: center;
  max-width: 660px;

  h1 {
    margin: 0 0 14px;
    color: #201b2d;
    font-size: 36px;
    line-height: 1.18;
  }

  p:not(.eyebrow) {
    margin: 0;
    color: #766f82;
    font-size: 16px;
    line-height: 1.85;
  }
}

.eyebrow {
  margin: 0 0 8px;
  color: #6d4df7;
  font-size: 12px;
  font-weight: 950;
  letter-spacing: 0;
}

.welcome-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 24px;

  :deep(.el-button) {
    min-height: 42px;
    border-radius: 8px;
    font-weight: 900;
  }
}

.desk-scene {
  position: relative;
  min-height: 260px;
  overflow: hidden;
  border-radius: 8px;
  background: #c9c3d6;
}

.helper,
.paper {
  position: absolute;
  box-shadow: 0 18px 36px rgba(65, 49, 98, 0.13);
}

.helper-purple {
  left: 40%;
  top: 13%;
  width: 29%;
  height: 68%;
  border-radius: 8px 8px 14px 14px;
  background: #6d4df7;
  transform: skewX(-10deg);
  animation: helperFloat 5s ease-in-out infinite;
}

.helper-black {
  left: 53%;
  top: 42%;
  width: 24%;
  height: 45%;
  border-radius: 8px 8px 0 0;
  background: #1f1d22;
  animation: helperFloat 4.7s ease-in-out 0.2s infinite;
}

.helper-yellow {
  right: 6%;
  bottom: 7%;
  width: 30%;
  height: 42%;
  border-radius: 99px 99px 8px 8px;
  background: #f8de43;
  animation: helperFloat 5.4s ease-in-out 0.45s infinite;
}

.helper-orange {
  left: 8%;
  bottom: 7%;
  width: 43%;
  height: 38%;
  border-radius: 999px 999px 8px 8px;
  background: #ff9c5d;
  animation: helperFloat 5.8s ease-in-out 0.1s infinite;
}

.eye {
  position: absolute;
  top: 20%;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fff;
  animation: blink 4.2s ease-in-out infinite;

  &::after {
    content: "";
    position: absolute;
    right: 2px;
    bottom: 2px;
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: #1f1d22;
  }
}

.helper-orange .eye,
.helper-yellow .eye {
  width: 9px;
  height: 9px;
  background: #1f1d22;

  &::after {
    display: none;
  }
}

.eye.left {
  left: 35%;
}

.eye.right {
  right: 25%;
}

.helper-orange .eye {
  top: 48%;
}

.helper-yellow .eye {
  top: 31%;
}

.mouth {
  position: absolute;
  left: 42%;
  top: 58%;
  width: 34%;
  height: 4px;
  border-radius: 99px;
  background: #1f1d22;
}

.paper {
  display: grid;
  place-items: center;
  width: 86px;
  height: 48px;
  border-radius: 8px;
  color: #201b2d;
  font-weight: 950;
  background: #ffffff;
}

.paper-one {
  left: 10%;
  top: 16%;
  transform: rotate(-8deg);
  animation: paperDrift 4.5s ease-in-out infinite;
}

.paper-two {
  right: 13%;
  top: 12%;
  background: #e9fff7;
  transform: rotate(7deg);
  animation: paperDrift 5s ease-in-out 0.5s infinite;
}

.bubble-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin: 16px 0;
}

.bubble-card {
  display: grid;
  gap: 8px;
  min-height: 150px;
  padding: 18px;
  border: 0;
  border-radius: 8px;
  color: #201b2d;
  text-align: left;
  cursor: pointer;
  box-shadow: 0 14px 30px rgba(71, 53, 109, 0.11);
  transition: transform 0.18s ease, box-shadow 0.18s ease;
  animation: cardPop 0.55s ease both;

  @for $i from 1 through 4 {
    &:nth-child(#{$i}) {
      animation-delay: #{$i * 0.06}s;
    }
  }

  &:hover {
    transform: translateY(-6px) rotate(-1deg);
    box-shadow: 0 22px 40px rgba(71, 53, 109, 0.16);
  }

  .el-icon {
    font-size: 26px;
  }

  span,
  small {
    color: rgba(32, 27, 45, 0.62);
    font-weight: 800;
  }

  strong {
    font-size: 30px;
    line-height: 1;
  }
}

.dashboard-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 40px;
  margin: 8px 0 4px;

  > div {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  small {
    color: #7d7488;
  }
}

.dashboard-state {
  display: grid;
  place-items: center;
  gap: 10px;
  min-height: 260px;
  padding: 30px;
  border: 1px dashed rgba(108, 84, 180, 0.24);
  border-radius: 8px;
  color: #7d7488;
  background: rgba(255, 253, 248, 0.78);
}

.service-chart {
  width: 100%;
  height: 240px;
}

.trend-caption {
  color: #8b8494;
}

.dashboard-error strong {
  color: #c45656;
}

.state-spinner {
  width: 30px;
  height: 30px;
  border: 3px solid rgba(109, 77, 247, 0.18);
  border-top-color: #6d4df7;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

.mint { background: #c9f7e8; }
.blue { background: #cfe7ff; }
.banana { background: #ffe889; }
.coral { background: #ffb58b; }

.play-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(300px, 0.75fr);
  gap: 16px;
}

.panel {
  padding: 20px;
  border: 1px solid rgba(108, 84, 180, 0.14);
  border-radius: 8px;
  background: rgba(255, 253, 248, 0.92);
  box-shadow: 0 14px 32px rgba(76, 59, 112, 0.09);
}

.panel-wide {
  min-width: 0;
}

.panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;

  h2 {
    margin: 0;
    color: #201b2d;
    font-size: 19px;
  }
}

.health-map {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.health-item {
  padding: 16px;
  border-radius: 8px;
  background: #f7f3ff;
}

.health-top {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
  color: #201b2d;
  font-weight: 950;
}

.health-track {
  height: 10px;
  overflow: hidden;
  border-radius: 99px;
  background: rgba(32, 27, 45, 0.08);

  i {
    display: block;
    height: 100%;
    border-radius: inherit;
    background: currentColor;
    animation: fillIn 0.8s ease both;
  }
}

.health-item small {
  display: block;
  margin-top: 10px;
  color: #7d7488;
}

.inline-empty,
.empty-task {
  color: #8b8494;
  text-align: center;
}

.sticker-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;

  button {
    display: grid;
    place-items: center;
    gap: 8px;
    min-height: 92px;
    border: 0;
    border-radius: 8px;
    color: #201b2d;
    font-weight: 950;
    background: #f0ebff;
    cursor: pointer;
    transition: transform 0.18s ease, background 0.18s ease;

    &:hover {
      transform: rotate(-2deg) translateY(-4px);
      background: #e7ddff;
    }

    .el-icon {
      font-size: 24px;
    }
  }
}

.task-count {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 8px;
  color: #201b2d;
  font-weight: 950;
  background: #ffe889;
}

.task-list {
  display: grid;
  gap: 14px;
  padding: 0;
  margin: 0;
  list-style: none;

  li {
    display: flex;
    gap: 12px;
    padding: 12px;
    border-radius: 8px;
    background: #faf7ff;
  }

  strong,
  small {
    display: block;
  }

  strong {
    color: #201b2d;
  }

  small {
    margin-top: 4px;
    color: #7d7488;
    line-height: 1.6;
  }
}

.task-dot {
  flex: 0 0 auto;
  width: 10px;
  height: 10px;
  margin-top: 7px;
  border-radius: 50%;

  &.high { background: #ff7b7b; }
  &.medium { background: #f8de43; }
  &.low { background: #62d8ad; }
}

.activity-list {
  display: grid;
  gap: 10px;
}

.activity-item {
  display: grid;
  grid-template-columns: 58px minmax(120px, 0.7fr) minmax(160px, 1fr);
  gap: 12px;
  align-items: center;
  min-height: 44px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f8f4ff;

  span {
    color: #6d4df7;
    font-weight: 950;
  }

  strong {
    color: #201b2d;
  }

  small {
    color: #7d7488;
  }
}

@keyframes helperFloat {
  0%,
  100% { transform: translateY(0) rotate(0); }
  50% { transform: translateY(-10px) rotate(1deg); }
}

@keyframes paperDrift {
  0%,
  100% { translate: 0 0; }
  50% { translate: 0 -8px; }
}

@keyframes blink {
  0%,
  88%,
  100% { transform: scaleY(1); }
  92% { transform: scaleY(0.1); }
}

@keyframes cardPop {
  from {
    opacity: 0;
    transform: translateY(16px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

@keyframes fillIn {
  from { transform: scaleX(0); transform-origin: left; }
  to { transform: scaleX(1); transform-origin: left; }
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 1180px) {
  .welcome-band,
  .play-grid {
    grid-template-columns: 1fr;
  }

  .bubble-grid,
  .health-map {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .desk-scene {
    min-height: 220px;
  }
}

@media (max-width: 760px) {
  .home-page {
    padding: 12px;
  }

  .welcome-band {
    padding: 20px;
  }

  .welcome-copy h1 {
    font-size: 27px;
  }

  .bubble-grid,
  .health-map,
  .sticker-grid,
  .activity-item {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
  .helper,
  .paper,
  .eye,
  .bubble-card,
  .health-track i,
  .state-spinner {
    animation: none !important;
  }
}

/* 首页轻快主题：像一张有秩序的桌面便签，而不是冷冰冰的监控墙。 */
.home-page {
  color: #2b3044;
  background:
    radial-gradient(circle at 12% 4%, rgba(143,225,199,.25), transparent 28%),
    radial-gradient(circle at 92% 0%, rgba(255,205,143,.24), transparent 24%),
    linear-gradient(90deg, rgba(255,255,255,.58) 1px, transparent 1px),
    linear-gradient(0deg, rgba(255,255,255,.58) 1px, transparent 1px),
    #fff9f2;
  background-size: auto, auto, 38px 38px, 38px 38px, auto;
  font-family: "Avenir Next", "Noto Sans SC", "PingFang SC", "Segoe UI", sans-serif;
}
.welcome-band { border-color: rgba(69,185,158,.18); border-radius: 18px; background: rgba(255,253,249,.94); box-shadow: 0 18px 46px rgba(77,65,99,.10); }
.welcome-copy h1 { color: #2b3044; font-weight: 700; letter-spacing: -.035em; }
.welcome-copy p:not(.eyebrow) { color: #77798b; }
.eyebrow { color: #2f9b86; font-size: 11px; font-weight: 800; letter-spacing: .04em; }
.welcome-actions :deep(.el-button) { border-radius: 10px; font-weight: 700; }
.welcome-actions :deep(.el-button--primary) { border-color: #45b99e; background: #45b99e; box-shadow: 0 10px 20px rgba(69,185,158,.18); }
.welcome-actions :deep(.el-button--primary:hover) { border-color: #36a88d; background: #36a88d; }
.desk-scene { border-radius: 18px; background: linear-gradient(135deg, #b7e9de, #c7e9ff 58%, #ffe0b4); }
.helper, .paper { box-shadow: 0 18px 36px rgba(77,65,99,.14); }
.helper-purple { background: #786cf2; }
.helper-black { background: #2b3044; }
.helper-yellow { background: #ffe37a; }
.helper-orange { background: #ffad86; }
.paper { color: #2b3044; border: 1px solid rgba(43,48,68,.06); }
.paper-two { background: #effcf7; }
.dashboard-toolbar small, .trend-caption { color: #888a9b; }
.dashboard-state { border-color: rgba(69,185,158,.28); border-radius: 14px; color: #77798b; background: rgba(255,253,249,.84); }
.dashboard-error strong { color: #d66e6e; }
.state-spinner { border-color: rgba(69,185,158,.18); border-top-color: #45b99e; }
.mint { background: #dff7ee; }
.blue { background: #e2f2ff; }
.banana { background: #fff0b4; }
.coral { background: #ffe0ce; }
.bubble-card { border-radius: 16px; color: #2b3044; box-shadow: 0 14px 30px rgba(77,65,99,.10); }
.bubble-card:hover { box-shadow: 0 22px 40px rgba(77,65,99,.15); }
.panel { border-color: rgba(69,185,158,.14); border-radius: 16px; background: rgba(255,253,249,.94); box-shadow: 0 14px 32px rgba(77,65,99,.08); }
.panel-header h2 { color: #2b3044; font-weight: 700; }
.health-item { border-radius: 12px; background: #f7fbf9; }
.health-item.mint { background: #e9faf3; }
.health-item.blue { background: #edf7ff; }
.health-item.banana { background: #fff8dc; }
.health-item.coral { background: #fff0e6; }
.health-top { color: #2b3044; font-weight: 700; }
.health-track { background: rgba(43,48,68,.08); }
.health-item small, .inline-empty, .empty-task { color: #888a9b; }
.sticker-grid button { border: 1px solid rgba(69,185,158,.12); border-radius: 14px; color: #2b3044; background: #eefaf5; }
.sticker-grid button:hover { background: #dff7ee; }
.task-count { color: #2b3044; background: #ffe37a; }
.task-list li { border: 1px solid rgba(69,185,158,.10); border-radius: 12px; background: #fffaf4; }
.task-list strong, .activity-item strong { color: #2b3044; }
.task-list small, .activity-item small { color: #888a9b; }
.activity-item { border: 1px solid rgba(69,185,158,.10); border-radius: 11px; background: #f3fbf7; }
.activity-item span { color: #2f9b86; }
.home-page :deep(.el-tag) { border-color: rgba(69,185,158,.24); color: #2f9b86; background: rgba(69,185,158,.08); }
.home-page :deep(.el-button) { --el-button-text-color: #7d7e91; --el-button-hover-text-color: #2f9b86; --el-button-hover-bg-color: rgba(69,185,158,.08); }
@media (max-width: 760px) { .welcome-band { border-radius: 14px; } .panel { border-radius: 14px; } }
</style>

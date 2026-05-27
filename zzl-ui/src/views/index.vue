<template>
  <div class="home-page">
    <section class="welcome-band">
      <div class="welcome-copy">
        <p class="eyebrow">Zzl Happy Console</p>
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

    <section class="bubble-grid">
      <button v-for="item in metrics" :key="item.label" class="bubble-card" :class="item.tone" type="button">
        <el-icon><component :is="item.icon" /></el-icon>
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
        <small>{{ item.trend }}</small>
      </button>
    </section>

    <section class="play-grid">
      <div class="panel panel-wide">
        <div class="panel-header">
          <div>
            <p class="eyebrow">Live Map</p>
            <h2>系统活力地图</h2>
          </div>
          <el-tag type="success">运行中</el-tag>
        </div>
        <div class="health-map">
          <div v-for="service in services" :key="service.name" class="health-item" :class="service.tone">
            <div class="health-top">
              <span>{{ service.name }}</span>
              <strong>{{ service.percent }}%</strong>
            </div>
            <div class="health-track">
              <i :style="{ width: service.percent + '%' }"></i>
            </div>
          </div>
        </div>
      </div>

      <div class="panel sticker-panel">
        <div class="panel-header">
          <div>
            <p class="eyebrow">Shortcuts</p>
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
            <p class="eyebrow">Today</p>
            <h2>今日小任务</h2>
          </div>
          <span class="task-count">12</span>
        </div>
        <ul class="task-list">
          <li v-for="task in tasks" :key="task.title">
            <span :class="['task-dot', task.level]"></span>
            <div>
              <strong>{{ task.title }}</strong>
              <small>{{ task.desc }}</small>
            </div>
          </li>
        </ul>
      </div>

      <div class="panel panel-wide activity-panel">
        <div class="panel-header">
          <div>
            <p class="eyebrow">Timeline</p>
            <h2>最近发生了什么</h2>
          </div>
          <el-button text icon="Refresh">刷新</el-button>
        </div>
        <div class="activity-list">
          <div v-for="item in activities" :key="item.time" class="activity-item">
            <span>{{ item.time }}</span>
            <strong>{{ item.title }}</strong>
            <small>{{ item.detail }}</small>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
const router = useRouter()

const metrics = [
  { label: '在线用户', value: '1,248', trend: '比昨天更热闹 +12.6%', icon: 'UserFilled', tone: 'mint' },
  { label: '接口成功率', value: '99.92%', trend: '小火箭稳定飞行', icon: 'Connection', tone: 'blue' },
  { label: '待办流程', value: '36', trend: '8 项需要优先看', icon: 'Tickets', tone: 'banana' },
  { label: '提醒事件', value: '4', trend: '已经收敛 2 项', icon: 'WarningFilled', tone: 'coral' }
]

const services = [
  { name: 'API Gateway', percent: 98, tone: 'mint' },
  { name: 'Redis Cache', percent: 94, tone: 'blue' },
  { name: 'MySQL Cluster', percent: 89, tone: 'banana' },
  { name: 'Quartz Jobs', percent: 96, tone: 'coral' }
]

const shortcuts = [
  { label: '用户管理', path: '/system/user', icon: 'User' },
  { label: '角色权限', path: '/system/role', icon: 'Lock' },
  { label: '菜单配置', path: '/system/menu', icon: 'Menu' },
  { label: '代码生成', path: '/tool/gen', icon: 'MagicStick' }
]

const tasks = [
  { title: '审核新增角色权限', desc: '系统管理模块有 3 条变更待确认', level: 'high' },
  { title: '检查定时任务失败记录', desc: '最近 24 小时出现 2 次重试', level: 'medium' },
  { title: '同步通知公告状态', desc: '5 条公告需要更新阅读范围', level: 'low' }
]

const activities = [
  { time: '09:42', title: 'admin 登录系统', detail: '来自 127.0.0.1，已通过安全校验' },
  { time: '10:18', title: '系统参数更新', detail: '验证码开关与文件上传策略已保存' },
  { time: '11:05', title: '生成模块代码', detail: 'tool/gen 输出 Vue3 + Element Plus 模板' },
  { time: '13:26', title: '权限缓存刷新', detail: '菜单与角色授权数据已重新载入' }
]

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
  .health-track i {
    animation: none !important;
  }
}
</style>

<template>
  <div class="social-callback">
    <div class="callback-card">
      <span class="callback-mark" aria-hidden="true"></span>
      <h1>{{ error ? '第三方登录失败' : '正在进入工作台' }}</h1>
      <p>{{ error || '授权成功，正在为你打开工作台...' }}</p>
      <el-button type="primary" @click="goLogin">{{ error ? '返回登录' : '立即进入' }}</el-button>
    </div>
  </div>
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { setToken } from '@/utils/auth'

const route = useRoute()
const router = useRouter()
const error = ref('')
const redirect = computed(() => {
  const value = route.query.redirect
  return typeof value === 'string' && value.startsWith('/') ? value : '/index'
})

function goLogin() {
  router.replace(error.value ? '/login' : redirect.value)
}

onMounted(() => {
  const token = route.query.token
  const errorMessage = route.query.error
  if (typeof token === 'string' && token) {
    setToken(token)
    router.replace(redirect.value)
    return
  }
  error.value = typeof errorMessage === 'string' ? errorMessage : '未获取到授权令牌'
  ElMessage.error(error.value)
})
</script>

<style lang="scss" scoped>
.social-callback {
  display: grid;
  min-height: 100%;
  place-items: center;
  padding: 24px;
  background:
    radial-gradient(circle at 20% 20%, rgba(255, 216, 77, 0.3), transparent 26%),
    radial-gradient(circle at 80% 18%, rgba(127, 183, 255, 0.28), transparent 28%),
    #fbf8ff;
}

.callback-card {
  width: min(420px, 100%);
  padding: 34px;
  border: 1px solid rgba(109, 77, 247, 0.1);
  border-radius: 8px;
  background: #fffefb;
  box-shadow: 0 24px 60px rgba(79, 62, 168, 0.14);
  text-align: center;

  h1 {
    margin: 18px 0 10px;
    color: #17151f;
    font-size: 26px;
  }

  p {
    margin: 0 0 24px;
    color: #81798d;
    font-weight: 800;
  }
}

.callback-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 58px;
  height: 58px;
  border-radius: 8px;
  background: #17151f;
  color: #ffffff;
  font-size: 24px;
  font-weight: 950;
  animation: pop 1.8s ease-in-out infinite;
}

@keyframes pop {
  0%,
  100% { transform: translateY(0) rotate(0); }
  50% { transform: translateY(-6px) rotate(-2deg); }
}
</style>

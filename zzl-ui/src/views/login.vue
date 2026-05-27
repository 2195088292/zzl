<template>
  <div
    class="login"
    :class="{ 'password-is-visible': passwordVisible }"
    :style="eyeStyle"
    @focusin="handleLoginFocusIn"
    @focusout="handleLoginFocusOut"
    @pointermove="handlePointerMove"
    @pointerleave="resetEyes"
  >
    <section class="story-panel" aria-hidden="true">
      <div class="story-brand">
        <span class="brand-spark">Z</span>
        <strong>Zzl Playground</strong>
      </div>

      <div ref="mascotStageRef" class="mascot-stage" :class="mascotClasses">
        <div class="shape shape-back">
          <span class="eye left"></span>
          <span class="eye right"></span>
        </div>
        <div class="shape shape-front">
          <span class="eye left"></span>
          <span class="eye right"></span>
        </div>
        <div class="shape shape-sun">
          <span class="eye left"></span>
          <span class="eye right"></span>
          <span class="mouth"></span>
        </div>
        <div class="shape shape-peach">
          <span class="eye left"></span>
          <span class="eye right"></span>
        </div>
        <div class="sparkle sparkle-one"></div>
        <div class="sparkle sparkle-two"></div>
        <div class="sparkle sparkle-three"></div>
      </div>

      <div class="story-copy">
        <h2>把后台做得有趣一点</h2>
        <p>轻快的颜色、会呼吸的小角色、顺滑的微动效，让每天打开系统都不那么无聊。</p>
      </div>
    </section>

    <section class="form-panel">
      <div class="tiny-star" aria-hidden="true"></div>
      <el-form ref="loginRef" :model="loginForm" :rules="loginRules" class="login-form">
        <div class="form-title">
          <span class="mini-mark">Z</span>
          <p>Welcome back!</p>
          <h1>{{ title }}</h1>
          <small>输入账号，开启今天的管理小冒险</small>
        </div>

        <label class="field-label">账号</label>
        <el-form-item prop="username">
          <el-input
            v-model="loginForm.username"
            type="text"
            size="large"
            auto-complete="off"
            placeholder="请输入账号"
            @focus="handleCredentialFocus"
            @blur="handleCredentialBlur"
            @input="handleCredentialFocus"
          >
            <template #prefix><svg-icon icon-class="user" class="el-input__icon input-icon" /></template>
          </el-input>
        </el-form-item>

        <label class="field-label">密码</label>
        <el-form-item prop="password">
          <el-input
            v-model="loginForm.password"
            :type="passwordVisible ? 'text' : 'password'"
            size="large"
            auto-complete="off"
            placeholder="请输入密码"
            @focus="handleCredentialFocus"
            @blur="handleCredentialBlur"
            @input="handleCredentialFocus"
            @keyup.enter="handleLogin"
          >
            <template #prefix><svg-icon icon-class="password" class="el-input__icon input-icon" /></template>
            <template #suffix>
              <button
                class="password-toggle"
                type="button"
                :aria-pressed="passwordVisible"
                @mousedown.prevent
                @click.stop="togglePasswordVisible"
              >
                <svg-icon :icon-class="passwordVisible ? 'eye-open' : 'eye'" class="password-toggle-icon" />
              </button>
            </template>
          </el-input>
        </el-form-item>

        <template v-if="captchaEnabled">
          <label class="field-label">验证码</label>
          <el-form-item prop="code">
            <div class="captcha-row">
              <el-input
                v-model="loginForm.code"
                size="large"
                auto-complete="off"
                placeholder="请输入验证码"
                @keyup.enter="handleLogin"
              >
                <template #prefix><svg-icon icon-class="validCode" class="el-input__icon input-icon" /></template>
              </el-input>
              <button class="login-code" type="button" @click="getCode">
                <img :src="codeUrl" class="login-code-img" alt="验证码" />
              </button>
            </div>
          </el-form-item>
        </template>

        <div class="login-options">
          <el-checkbox v-model="loginForm.rememberMe">记住密码</el-checkbox>
          <router-link v-if="register" class="link-type" :to="'/register'">立即注册</router-link>
        </div>

        <el-button
          :loading="loading"
          size="large"
          type="primary"
          class="login-button"
          @click.prevent="handleLogin"
        >
          <span v-if="!loading">登录</span>
          <span v-else>正在登录...</span>
        </el-button>

        <SocialAuth mode="login" :redirect="redirect || '/index'" />

        <p class="form-note">Zzl 会帮你把复杂工作整理得更轻松。</p>
      </el-form>

      <div class="el-login-footer">
        <span>{{ footerContent }}</span>
      </div>
    </section>
  </div>
</template>

<script setup>
import { getCodeImg } from "@/api/login"
import Cookies from "js-cookie"
import { encrypt, decrypt } from "@/utils/jsencrypt"
import useUserStore from '@/store/modules/user'
import defaultSettings from '@/settings'
import SocialAuth from '@/components/SocialAuth/index.vue'

const title = import.meta.env.VITE_APP_TITLE
const footerContent = defaultSettings.footerContent
const userStore = useUserStore()
const route = useRoute()
const router = useRouter()
const { proxy } = getCurrentInstance()

const loginForm = ref({
  username: "admin",
  password: "admin123",
  rememberMe: false,
  code: "",
  uuid: ""
})

const loginRules = {
  username: [{ required: true, trigger: "blur", message: "请输入您的账号" }],
  password: [{ required: true, trigger: "blur", message: "请输入您的密码" }],
  code: [{ required: true, trigger: "change", message: "请输入验证码" }]
}

const codeUrl = ref("")
const loading = ref(false)
const captchaEnabled = ref(true)
const register = ref(false)
const redirect = ref(undefined)
const passwordVisible = ref(false)
const credentialActive = ref(false)
const mascotStageRef = ref(null)
const eyeOffset = reactive({ x: 0, y: 0 })
let pointerFrame = 0
let blurTimer = 0

const mascotClasses = computed(() => ({
  'is-peeking': credentialActive.value && !passwordVisible.value,
  'is-turned': passwordVisible.value
}))

const eyeStyle = computed(() => ({
  '--eye-x': `${passwordVisible.value ? -6 : eyeOffset.x}px`,
  '--eye-y': `${passwordVisible.value ? 1 : eyeOffset.y}px`
}))

watch(route, (newRoute) => {
  redirect.value = newRoute.query && newRoute.query.redirect
}, { immediate: true })

function handleLogin() {
  proxy.$refs.loginRef.validate(valid => {
    if (valid) {
      loading.value = true
      if (loginForm.value.rememberMe) {
        Cookies.set("username", loginForm.value.username, { expires: 30 })
        Cookies.set("password", encrypt(loginForm.value.password), { expires: 30 })
        Cookies.set("rememberMe", loginForm.value.rememberMe, { expires: 30 })
      } else {
        Cookies.remove("username")
        Cookies.remove("password")
        Cookies.remove("rememberMe")
      }
      userStore.login(loginForm.value).then(() => {
        const query = route.query
        const otherQueryParams = Object.keys(query).reduce((acc, cur) => {
          if (cur !== "redirect") {
            acc[cur] = query[cur]
          }
          return acc
        }, {})
        router.push({ path: redirect.value || "/", query: otherQueryParams })
      }).catch(() => {
        loading.value = false
        if (captchaEnabled.value) {
          getCode()
        }
      })
    }
  })
}

function getCode() {
  getCodeImg().then(res => {
    captchaEnabled.value = res.captchaEnabled === undefined ? true : res.captchaEnabled
    if (captchaEnabled.value) {
      codeUrl.value = "data:image/gif;base64," + res.img
      loginForm.value.uuid = res.uuid
    }
  })
}

function getCookie() {
  const username = Cookies.get("username")
  const password = Cookies.get("password")
  const rememberMe = Cookies.get("rememberMe")
  loginForm.value = {
    ...loginForm.value,
    username: username === undefined ? loginForm.value.username : username,
    password: password === undefined ? loginForm.value.password : decrypt(password),
    rememberMe: rememberMe === undefined ? false : Boolean(rememberMe)
  }
}

function handlePointerMove(event) {
  if (passwordVisible.value || !mascotStageRef.value) {
    return
  }
  const rect = mascotStageRef.value.getBoundingClientRect()
  const centerX = rect.left + rect.width / 2
  const centerY = rect.top + rect.height / 2
  const nextX = Math.max(-1, Math.min(1, (event.clientX - centerX) / (rect.width / 2)))
  const nextY = Math.max(-1, Math.min(1, (event.clientY - centerY) / (rect.height / 2)))
  cancelAnimationFrame(pointerFrame)
  pointerFrame = requestAnimationFrame(() => {
    eyeOffset.x = Math.round(nextX * 5)
    eyeOffset.y = Math.round(nextY * 4)
  })
}

function resetEyes() {
  if (passwordVisible.value) {
    return
  }
  cancelAnimationFrame(pointerFrame)
  eyeOffset.x = 0
  eyeOffset.y = 0
}

function handleCredentialFocus() {
  clearTimeout(blurTimer)
  if (!passwordVisible.value) {
    credentialActive.value = true
  }
}

function handleCredentialBlur() {
  clearTimeout(blurTimer)
  blurTimer = setTimeout(() => {
    credentialActive.value = false
  }, 140)
}

function isCredentialField(target) {
  return target?.tagName === 'INPUT'
    && target.closest?.('.login-form')
    && !target.closest?.('.captcha-row')
}

function handleLoginFocusIn(event) {
  if (isCredentialField(event.target)) {
    handleCredentialFocus()
  }
}

function handleLoginFocusOut(event) {
  if (isCredentialField(event.target)) {
    handleCredentialBlur()
  }
}

function handleDocumentFocusIn(event) {
  handleLoginFocusIn(event)
}

function handleDocumentFocusOut(event) {
  handleLoginFocusOut(event)
}

function togglePasswordVisible() {
  passwordVisible.value = !passwordVisible.value
  if (passwordVisible.value) {
    credentialActive.value = false
    eyeOffset.x = -6
    eyeOffset.y = 1
  } else {
    handleCredentialFocus()
  }
}

onMounted(() => {
  document.addEventListener('focusin', handleDocumentFocusIn, true)
  document.addEventListener('focusout', handleDocumentFocusOut, true)
})

onBeforeUnmount(() => {
  cancelAnimationFrame(pointerFrame)
  clearTimeout(blurTimer)
  document.removeEventListener('focusin', handleDocumentFocusIn, true)
  document.removeEventListener('focusout', handleDocumentFocusOut, true)
})

getCode()
getCookie()
</script>

<style lang="scss" scoped>
.login {
  position: relative;
  display: grid;
  grid-template-columns: minmax(440px, 1.04fr) minmax(420px, 0.96fr);
  min-height: 100%;
  overflow: hidden;
  color: #17151f;
  background: #fbfaf7;
}

.story-panel {
  position: relative;
  display: grid;
  grid-template-rows: auto 1fr auto;
  min-height: 100vh;
  padding: 44px 54px;
  overflow: hidden;
  background:
    linear-gradient(90deg, rgba(255, 255, 255, 0.24) 1px, transparent 1px),
    linear-gradient(0deg, rgba(255, 255, 255, 0.18) 1px, transparent 1px),
    #c8c1d4;
  background-size: 56px 56px;
}

.story-brand {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 12px;
  color: #ffffff;
  font-size: 21px;
  font-weight: 900;
}

.brand-spark,
.mini-mark {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 8px;
  color: #16111f;
  font-weight: 950;
  background: #ffffff;
  box-shadow: 0 12px 26px rgba(84, 63, 135, 0.16);
}

.mascot-stage {
  position: relative;
  align-self: center;
  justify-self: center;
  width: min(620px, 88%);
  aspect-ratio: 1.1;
  perspective: 900px;
  transform-style: preserve-3d;
  transform: translateY(10px);
  transition: transform 0.38s cubic-bezier(0.2, 0.8, 0.2, 1);
}

.shape {
  position: absolute;
  display: block;
  box-shadow: 0 28px 54px rgba(77, 62, 100, 0.14);
  transform-origin: bottom center;
  transform-style: preserve-3d;
  transition:
    left 0.44s cubic-bezier(0.2, 0.8, 0.2, 1),
    right 0.44s cubic-bezier(0.2, 0.8, 0.2, 1),
    top 0.44s cubic-bezier(0.2, 0.8, 0.2, 1),
    bottom 0.44s cubic-bezier(0.2, 0.8, 0.2, 1),
    width 0.44s cubic-bezier(0.2, 0.8, 0.2, 1),
    height 0.44s cubic-bezier(0.2, 0.8, 0.2, 1),
    border-radius 0.44s cubic-bezier(0.2, 0.8, 0.2, 1),
    transform 0.48s cubic-bezier(0.2, 0.8, 0.2, 1),
    filter 0.28s ease,
    box-shadow 0.28s ease;

  &::before {
    content: "";
    position: absolute;
    inset: 9%;
    border-radius: inherit;
    background:
      linear-gradient(120deg, rgba(255, 255, 255, 0.16), transparent 42%),
      repeating-linear-gradient(90deg, rgba(255, 255, 255, 0.12) 0 2px, transparent 2px 14px);
    opacity: 0;
    transform: rotateY(180deg) translateZ(2px);
    transition: opacity 0.18s ease 0.1s;
  }
}

.shape-back {
  left: 39%;
  top: 33%;
  width: 30%;
  height: 48%;
  border-radius: 999px 999px 12px 12px;
  background: #6d4df7;
  transform: translateZ(8px) rotate(-5deg);
  animation: tallBuddy 4.8s ease-in-out infinite;
}

.shape-front {
  left: 55%;
  top: 39%;
  width: 26%;
  height: 42%;
  border-radius: 8px 8px 0 0;
  background: #222126;
  animation: smallBuddy 4.2s ease-in-out 0.2s infinite;
}

.shape-sun {
  right: 7%;
  bottom: 7%;
  width: 29%;
  height: 44%;
  border-radius: 999px 999px 8px 8px;
  background: #f7de3f;
  animation: sunnyBuddy 5s ease-in-out 0.4s infinite;
}

.shape-peach {
  left: 6%;
  bottom: 7%;
  width: 44%;
  height: 40%;
  border-radius: 999px 999px 8px 8px;
  background: #ff9c5d;
  animation: peachBuddy 5.4s ease-in-out 0.15s infinite;
}

.eye {
  position: absolute;
  top: 18%;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  overflow: hidden;
  background: #ffffff;
  animation: blink 4.5s ease-in-out infinite;

  &::after {
    content: "";
    position: absolute;
    right: 2px;
    bottom: 3px;
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #1f1d22;
    transform: translate(var(--eye-x, 0), var(--eye-y, 0));
    transition: transform 0.12s ease-out;
  }
}

.shape-peach .eye,
.shape-sun .eye {
  width: 15px;
  height: 15px;
  background: #ffffff;
  box-shadow: inset 0 0 0 1px rgba(31, 29, 34, 0.08);

  &::after {
    display: block;
    right: 2px;
    bottom: 3px;
    width: 6px;
    height: 6px;
  }
}

.eye.left {
  left: 38%;
}

.eye.right {
  right: 28%;
}

.shape-front .eye.left {
  left: 34%;
}

.shape-front .eye.right {
  right: 28%;
}

.shape-peach .eye {
  top: 46%;
}

.shape-peach .eye.left {
  left: 45%;
}

.shape-peach .eye.right {
  right: 31%;
}

.shape-sun .eye {
  top: 30%;
}

.shape-sun .eye.left {
  left: 44%;
}

.shape-sun .eye.right {
  right: 18%;
}

.mouth {
  position: absolute;
  left: 43%;
  top: 58%;
  width: 36%;
  height: 4px;
  border-radius: 99px;
  background: #242124;
  animation: mouthTalk 4.8s ease-in-out infinite;
}

.mascot-stage.is-peeking {
  transform: translateY(-4px) rotateX(4deg) rotateY(-4deg);
}

.mascot-stage.is-peeking .shape,
.mascot-stage.is-turned .shape {
  animation: none;
}

.mascot-stage.is-peeking .shape-back {
  left: 43%;
  top: 6%;
  width: 32%;
  height: 68%;
  border-radius: 8px 8px 16px 16px;
  transform: translate3d(18px, -42px, 58px) skewX(-11deg) rotate(-4deg) scale(1.04);
  box-shadow: 0 34px 64px rgba(77, 62, 100, 0.18);
}

.mascot-stage.is-peeking .shape-front {
  top: 30%;
  height: 52%;
  transform: translate3d(-2px, -34px, 54px) rotate(-3deg) scale(1.05);
}

.mascot-stage.is-peeking .shape-sun {
  height: 51%;
  transform: translate3d(-10px, -30px, 58px) rotate(2deg) scale(1.04);
}

.mascot-stage.is-peeking .shape-peach {
  height: 48%;
  transform: translate3d(8px, -28px, 44px) rotate(-1deg) scale(1.02);
}

.mascot-stage.is-turned {
  transform: translateY(6px) rotateX(4deg);
}

.mascot-stage.is-turned .shape {
  filter: saturate(0.9) brightness(0.96);
  box-shadow: -16px 24px 44px rgba(77, 62, 100, 0.13);
}

.mascot-stage.is-turned .shape::before {
  opacity: 1;
}

.mascot-stage.is-turned .eye,
.mascot-stage.is-turned .mouth {
  opacity: 0;
  transition: opacity 0.14s ease;
}

.mascot-stage.is-turned .shape-back {
  left: 39%;
  top: 33%;
  width: 30%;
  height: 48%;
  border-radius: 999px 999px 12px 12px;
  transform: translate3d(6px, 4px, 20px) rotateY(168deg) rotateZ(4deg);
}

.mascot-stage.is-turned .shape-front {
  transform: translate3d(2px, 8px, 8px) rotateY(174deg) rotateZ(-2deg);
}

.mascot-stage.is-turned .shape-sun {
  transform: translate3d(-12px, 8px, 28px) rotateY(170deg) rotateZ(4deg);
}

.mascot-stage.is-turned .shape-peach {
  transform: translate3d(10px, 8px, 10px) rotateY(166deg) rotateZ(-3deg);
}

.mascot-stage.is-turned .eye::after {
  transform: translate(-6px, 1px);
  transition-duration: 0.24s;
}

.sparkle {
  position: absolute;
  width: 32px;
  height: 32px;

  &::before,
  &::after {
    content: "";
    position: absolute;
    left: 50%;
    top: 50%;
    border-radius: 99px;
    background: rgba(255, 255, 255, 0.88);
    transform: translate(-50%, -50%);
  }

  &::before {
    width: 5px;
    height: 100%;
  }

  &::after {
    width: 100%;
    height: 5px;
  }
}

.sparkle-one {
  left: 2%;
  top: 6%;
  animation: sparklePop 3.8s ease-in-out infinite;
}

.sparkle-two {
  right: 12%;
  top: 16%;
  transform: scale(0.72);
  animation: sparklePop 4.2s ease-in-out 0.4s infinite;
}

.sparkle-three {
  left: 18%;
  top: 22%;
  transform: scale(0.52);
  animation: sparklePop 4.6s ease-in-out 0.8s infinite;
}

.story-copy {
  position: relative;
  z-index: 2;
  max-width: 520px;
  color: #ffffff;

  h2 {
    margin: 0 0 10px;
    font-size: 28px;
  }

  p {
    margin: 0;
    color: rgba(255, 255, 255, 0.76);
    line-height: 1.8;
  }
}

.form-panel {
  position: relative;
  display: grid;
  place-items: center;
  min-height: 100vh;
  padding: 54px;
  background: #fffefb;
}

.tiny-star {
  position: absolute;
  top: 9%;
  left: 50%;
  width: 28px;
  height: 28px;
  color: #17151f;
  animation: starWiggle 4s ease-in-out infinite;

  &::before,
  &::after {
    content: "";
    position: absolute;
    left: 50%;
    top: 50%;
    border-radius: 99px;
    background: currentColor;
    transform: translate(-50%, -50%);
  }

  &::before {
    width: 4px;
    height: 100%;
  }

  &::after {
    width: 100%;
    height: 4px;
  }
}

.login-form {
  position: relative;
  z-index: 1;
  width: min(460px, 100%);
  color: #17151f;

  :deep(.el-form-item) {
    margin-bottom: 20px;
  }

  :deep(.el-input__wrapper) {
    height: 50px;
    border-radius: 0;
    padding: 0;
    background: transparent;
    box-shadow: 0 1px 0 #17151f;
  }

  :deep(.el-input__wrapper.is-focus) {
    box-shadow: 0 2px 0 #6d4df7;
  }

  :deep(.el-input__inner) {
    color: #17151f;
    font-weight: 700;
  }

  :deep(.el-input__inner::placeholder) {
    color: #c9c4cf;
    font-weight: 600;
  }
}

.form-title {
  margin-bottom: 34px;
  text-align: center;

  .mini-mark {
    margin: 0 auto 18px;
    color: #ffffff;
    background: #17151f;
    box-shadow: none;
  }

  p {
    margin: 0 0 8px;
    color: #17151f;
    font-size: 31px;
    font-weight: 950;
  }

  h1 {
    margin: 0;
    color: #81798d;
    font-size: 16px;
    font-weight: 800;
  }

  small {
    display: block;
    margin-top: 10px;
    color: #aaa3b2;
    font-weight: 700;
  }
}

.field-label {
  display: block;
  margin-bottom: 2px;
  color: #17151f;
  font-size: 14px;
  font-weight: 900;
}

.input-icon {
  width: 15px;
  height: 38px;
  margin: 0 12px 0 0;
  color: #b8b1c2;
}

.password-toggle {
  display: inline-grid;
  width: 32px;
  height: 32px;
  place-items: center;
  margin-left: 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #aaa3b2;
  cursor: pointer;
  transition: transform 0.18s ease, background 0.18s ease, color 0.18s ease;

  &:hover {
    color: #6d4df7;
    background: rgba(109, 77, 247, 0.09);
    transform: translateY(-1px);
  }
}

.password-toggle-icon {
  width: 16px;
  height: 16px;
}

.captcha-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px;
  gap: 14px;
  width: 100%;
}

.login-code {
  height: 50px;
  padding: 0;
  border: 1px solid #e5e1eb;
  border-radius: 8px;
  overflow: hidden;
  background: #f8f6fb;
  cursor: pointer;
  transition: transform 0.18s ease, box-shadow 0.18s ease;

  &:hover {
    transform: translateY(-2px);
    box-shadow: 0 10px 20px rgba(109, 77, 247, 0.12);
  }
}

.login-code-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.login-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 34px;
  margin: 6px 0 20px;

  :deep(.el-checkbox__label),
  .link-type {
    color: #82798e;
    font-weight: 800;
  }

  :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
    border-color: #6d4df7;
    background-color: #6d4df7;
  }
}

.login-button {
  width: 100%;
  height: 56px;
  border: 0;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 950;
  color: #ffffff;
  background: linear-gradient(135deg, #6d4df7 0%, #7f5dff 46%, #ff9b5c 100%);
  box-shadow: 0 16px 30px rgba(109, 77, 247, 0.24);
  transition: transform 0.18s ease, box-shadow 0.18s ease, background 0.18s ease;

  &:hover,
  &:focus {
    transform: translateY(-2px);
    color: #ffffff;
    background: linear-gradient(135deg, #5c3ff0 0%, #6f55ff 44%, #ff8840 100%);
    box-shadow: 0 20px 36px rgba(109, 77, 247, 0.3);
  }
}

.form-note {
  margin: 20px 0 0;
  color: #aaa3b2;
  text-align: center;
  font-size: 13px;
  font-weight: 700;
}

.el-login-footer {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 26px;
  color: #b1aabb;
  text-align: center;
  font-size: 12px;
  font-weight: 700;
}

@keyframes tallBuddy {
  0%,
  100% { transform: translateZ(8px) translateY(0) rotate(-5deg); }
  50% { transform: translateZ(8px) translateY(-9px) rotate(-2deg); }
}

@keyframes smallBuddy {
  0%,
  100% { transform: translateY(0) rotate(0); }
  50% { transform: translateY(-10px) rotate(-2deg); }
}

@keyframes sunnyBuddy {
  0%,
  100% { transform: translateY(0) rotate(0); }
  50% { transform: translateY(-9px) rotate(2deg); }
}

@keyframes peachBuddy {
  0%,
  100% { transform: translateY(0) scaleX(1); }
  50% { transform: translateY(-8px) scaleX(1.02); }
}

@keyframes blink {
  0%,
  88%,
  100% { transform: scaleY(1); }
  92% { transform: scaleY(0.12); }
}

@keyframes lookAround {
  0%,
  100% { transform: translate(0, 0); }
  40% { transform: translate(-4px, 1px); }
  70% { transform: translate(2px, -1px); }
}

@keyframes mouthTalk {
  0%,
  100% { transform: scaleX(1); }
  45% { transform: scaleX(0.78); }
}

@keyframes sparklePop {
  0%,
  100% { opacity: 0.34; transform: scale(0.72) rotate(0deg); }
  50% { opacity: 1; transform: scale(1) rotate(90deg); }
}

@keyframes starWiggle {
  0%,
  100% { transform: rotate(0deg) scale(1); }
  50% { transform: rotate(45deg) scale(1.12); }
}

@media (max-width: 980px) {
  .login {
    grid-template-columns: 1fr;
    background: #c8c1d4;
  }

  .story-panel {
    min-height: 42vh;
    padding: 26px;
  }

  .story-copy {
    display: none;
  }

  .mascot-stage {
    width: min(430px, 82vw);
    margin-top: 8px;
  }

  .form-panel {
    min-height: 58vh;
    padding: 34px 24px 72px;
    border-radius: 8px 8px 0 0;
  }
}

@media (max-width: 520px) {
  .story-panel {
    min-height: 300px;
    padding: 20px 22px 16px;
  }

  .story-brand {
    font-size: 18px;
  }

  .mascot-stage {
    width: min(292px, 82vw);
    margin-top: 0;
    transform: translateY(-2px);
  }

  .form-panel {
    display: block;
    padding: 26px 26px 64px;
  }

  .tiny-star {
    display: none;
  }

  .form-title {
    margin-bottom: 20px;

    p {
      font-size: 26px;
    }
  }

  .captcha-row {
    grid-template-columns: minmax(0, 1fr) 118px;
    gap: 12px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .shape,
  .eye,
  .eye::after,
  .mouth,
  .sparkle,
  .tiny-star {
    animation: none !important;
  }
}
</style>

<template>
  <div class="register">
    <section class="register-story" aria-hidden="true">
      <div class="story-brand">
        <span>Z</span>
        <strong>Zzl Playground</strong>
      </div>

      <div class="join-stage">
        <div class="join-card card-a">
          <span class="eye left"></span>
          <span class="eye right"></span>
        </div>
        <div class="join-card card-b">
          <span class="eye left"></span>
          <span class="eye right"></span>
        </div>
        <div class="join-card card-c">
          <span class="eye left"></span>
          <span class="eye right"></span>
          <span class="smile"></span>
        </div>
        <span class="join-plus plus-a">+</span>
        <span class="join-plus plus-b">+</span>
      </div>

      <div class="story-copy">
        <h2>新成员，欢迎入场</h2>
        <p>让账号创建也轻快一点：明亮颜色、弹跳卡片和第三方快捷注册一起上。</p>
      </div>
    </section>

    <section class="register-panel">
      <el-form ref="registerRef" :model="registerForm" :rules="registerRules" class="register-form">
        <div class="form-title">
          <span class="mini-mark">Z</span>
          <p>Create account</p>
          <h1>{{ title }}</h1>
          <small>创建账号，加入 Zzl 工作台</small>
        </div>

        <label class="field-label">账号</label>
        <el-form-item prop="username">
          <el-input
            v-model="registerForm.username"
            type="text"
            size="large"
            auto-complete="off"
            placeholder="请输入账号"
          >
            <template #prefix><svg-icon icon-class="user" class="el-input__icon input-icon" /></template>
          </el-input>
        </el-form-item>

        <label class="field-label">密码</label>
        <el-form-item prop="password" :rules="registerPwdValidator">
          <el-input
            v-model="registerForm.password"
            type="password"
            size="large"
            auto-complete="off"
            show-password
            placeholder="请输入密码"
            @keyup.enter="handleRegister"
          >
            <template #prefix><svg-icon icon-class="password" class="el-input__icon input-icon" /></template>
          </el-input>
        </el-form-item>

        <label class="field-label">确认密码</label>
        <el-form-item prop="confirmPassword">
          <el-input
            v-model="registerForm.confirmPassword"
            type="password"
            size="large"
            auto-complete="off"
            show-password
            placeholder="请再次输入密码"
            @keyup.enter="handleRegister"
          >
            <template #prefix><svg-icon icon-class="password" class="el-input__icon input-icon" /></template>
          </el-input>
        </el-form-item>

        <template v-if="captchaEnabled">
          <label class="field-label">验证码</label>
          <el-form-item prop="code">
            <div class="captcha-row">
              <el-input
                v-model="registerForm.code"
                size="large"
                auto-complete="off"
                placeholder="请输入验证码"
                @keyup.enter="handleRegister"
              >
                <template #prefix><svg-icon icon-class="validCode" class="el-input__icon input-icon" /></template>
              </el-input>
              <button class="register-code" type="button" @click="getCode">
                <img :src="codeUrl" class="register-code-img" alt="验证码" />
              </button>
            </div>
          </el-form-item>
        </template>

        <el-button
          :loading="loading"
          size="large"
          type="primary"
          class="register-button"
          @click.prevent="handleRegister"
        >
          <span v-if="!loading">注册</span>
          <span v-else>正在注册...</span>
        </el-button>

        <SocialAuth mode="register" redirect="/index" />

        <div class="register-tip">
          <span>已经有账号？</span>
          <router-link class="link-type" :to="'/login'">使用已有账户登录</router-link>
        </div>
      </el-form>

      <div class="el-register-footer">
        <span>{{ footerContent }}</span>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ElMessageBox } from "element-plus"
import { getCodeImg, register } from "@/api/login"
import defaultSettings from '@/settings'
import { usePasswordRule } from "@/utils/passwordRule"
import SocialAuth from '@/components/SocialAuth/index.vue'

const title = import.meta.env.VITE_APP_TITLE
const footerContent = defaultSettings.footerContent
const router = useRouter()
const { proxy } = getCurrentInstance()
const { registerPwdValidator } = usePasswordRule()

const registerForm = ref({
  username: "",
  password: "",
  confirmPassword: "",
  code: "",
  uuid: ""
})

const equalToPassword = (rule, value, callback) => {
  if (registerForm.value.password !== value) {
    callback(new Error("两次输入的密码不一致"))
  } else {
    callback()
  }
}

const registerRules = {
  username: [
    { required: true, trigger: "blur", message: "请输入您的账号" },
    { min: 2, max: 20, message: "用户账号长度必须介于 2 和 20 之间", trigger: "blur" }
  ],
  confirmPassword: [
    { required: true, trigger: "blur", message: "请再次输入您的密码" },
    { required: true, validator: equalToPassword, trigger: "blur" }
  ],
  code: [{ required: true, trigger: "change", message: "请输入验证码" }]
}

const codeUrl = ref("")
const loading = ref(false)
const captchaEnabled = ref(true)

function handleRegister() {
  proxy.$refs.registerRef.validate(valid => {
    if (valid) {
      loading.value = true
      register(registerForm.value).then(() => {
        const username = registerForm.value.username
        ElMessageBox.alert(`<font color='red'>恭喜你，账号 ${username} 注册成功！</font>`, "系统提示", {
          dangerouslyUseHTMLString: true,
          type: "success"
        }).then(() => {
          router.push("/login")
        }).catch(() => {})
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
      registerForm.value.uuid = res.uuid
    }
  })
}

getCode()
</script>

<style lang="scss" scoped>
.register {
  display: grid;
  grid-template-columns: minmax(440px, 1.04fr) minmax(420px, 0.96fr);
  min-height: 100%;
  overflow: hidden;
  background: #fbfaf7;
  color: #17151f;
}

.register-story {
  position: relative;
  display: grid;
  grid-template-rows: auto 1fr auto;
  min-height: 100vh;
  padding: 44px 54px 48px;
  overflow: hidden;
  background:
    linear-gradient(90deg, rgba(255, 255, 255, 0.25) 1px, transparent 1px),
    linear-gradient(0deg, rgba(255, 255, 255, 0.18) 1px, transparent 1px),
    #c8c1d4;
  background-size: 56px 56px;
  color: #ffffff;
}

.story-brand {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  font-size: 20px;
  font-weight: 950;
  text-shadow: 0 12px 24px rgba(37, 29, 63, 0.18);

  span {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 42px;
    height: 42px;
    border-radius: 8px;
    color: #16111f;
    background: #ffffff;
    box-shadow: 0 12px 26px rgba(84, 63, 135, 0.16);
  }
}

.join-stage {
  position: relative;
  align-self: center;
  justify-self: center;
  width: min(590px, 88%);
  aspect-ratio: 1.12;
}

.join-card {
  position: absolute;
  border-radius: 8px 8px 28px 28px;
  box-shadow: 0 28px 54px rgba(77, 62, 100, 0.14);
  transform-origin: bottom center;
}

.card-a {
  left: 18%;
  bottom: 10%;
  width: 34%;
  height: 42%;
  background: #ff9c5d;
  animation: floatA 5.2s ease-in-out infinite;
}

.card-b {
  left: 43%;
  top: 8%;
  width: 30%;
  height: 62%;
  background: #6d4df7;
  transform: rotate(-7deg);
  animation: floatB 4.8s ease-in-out 0.2s infinite;
}

.card-c {
  right: 10%;
  bottom: 8%;
  width: 30%;
  height: 47%;
  background: #f7de3f;
  animation: floatC 5.6s ease-in-out 0.4s infinite;
}

.eye {
  position: absolute;
  top: 22%;
  width: 13px;
  height: 13px;
  border-radius: 50%;
  background: #242124;
  animation: blink 4.5s ease-in-out infinite;
}

.card-b .eye {
  width: 17px;
  height: 17px;
  background: #ffffff;

  &::after {
    content: "";
    position: absolute;
    right: 2px;
    bottom: 3px;
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #1f1d22;
  }
}

.eye.left { left: 40%; }
.eye.right { left: 55%; }

.smile {
  position: absolute;
  left: 41%;
  top: 49%;
  width: 46px;
  height: 4px;
  border-radius: 999px;
  background: #242124;
  animation: smile 4s ease-in-out infinite;
}

.join-plus {
  position: absolute;
  color: rgba(255, 255, 255, 0.86);
  font-size: 46px;
  font-weight: 900;
  animation: sparklePop 3s ease-in-out infinite;
}

.plus-a { left: 10%; top: 18%; }
.plus-b { right: 6%; top: 24%; animation-delay: 0.4s; }

.story-copy {
  max-width: 580px;
  text-shadow: 0 14px 30px rgba(37, 29, 63, 0.18);

  h2 {
    margin: 0 0 12px;
    font-size: clamp(30px, 4vw, 46px);
    line-height: 1.08;
  }

  p {
    margin: 0;
    color: rgba(255, 255, 255, 0.78);
    font-size: 16px;
    line-height: 1.8;
  }
}

.register-panel {
  position: relative;
  display: grid;
  min-height: 100vh;
  place-items: center;
  padding: 44px 60px 70px;
  background: #fffefb;
}

.register-form {
  width: min(460px, 100%);

  :deep(.el-form-item) {
    margin-bottom: 20px;
  }
}

.form-title {
  margin-bottom: 24px;
  text-align: center;

  p {
    margin: 18px 0 6px;
    color: #17151f;
    font-size: 32px;
    font-weight: 950;
  }

  h1 {
    margin: 0;
    color: #81798d;
    font-size: 15px;
  }

  small {
    display: block;
    margin-top: 10px;
    color: #aaa3b2;
    font-size: 13px;
    font-weight: 800;
  }
}

.mini-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  border-radius: 8px;
  background: #17151f;
  color: #ffffff;
  font-weight: 950;
}

.field-label {
  display: block;
  margin-bottom: 8px;
  color: #17151f;
  font-size: 14px;
  font-weight: 950;
}

:deep(.el-input__wrapper) {
  height: 50px;
  padding: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: 0 1px 0 #17151f;
}

:deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 2px 0 #6d4df7;
}

:deep(.el-input__inner) {
  color: #17151f;
  font-weight: 800;
}

:deep(.el-input__inner::placeholder) {
  color: #c9c4cf;
  font-weight: 700;
}

.input-icon {
  width: 15px;
  height: 38px;
  margin: 0 12px 0 0;
  color: #b8b1c2;
}

.captcha-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px;
  gap: 14px;
  width: 100%;
}

.register-code {
  height: 50px;
  border: 1px solid #ece7f3;
  border-radius: 8px;
  background: #f8f6fb;
  cursor: pointer;
}

.register-code-img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.register-button {
  width: 100%;
  height: 54px;
  border: 0;
  border-radius: 8px;
  color: #ffffff;
  font-size: 16px;
  font-weight: 950;
  background: linear-gradient(135deg, #6d4df7 0%, #7f5dff 46%, #ff9b5c 100%);
  box-shadow: 0 16px 30px rgba(109, 77, 247, 0.24);
}

.register-tip {
  display: flex;
  justify-content: center;
  gap: 8px;
  margin-top: 22px;
  color: #aaa3b2;
  font-size: 13px;
  font-weight: 800;
}

.link-type {
  color: #6d4df7;
  font-weight: 950;
}

.el-register-footer {
  position: absolute;
  right: 0;
  bottom: 22px;
  left: 0;
  color: #b1aabb;
  text-align: center;
  font-size: 12px;
  font-weight: 800;
}

@keyframes floatA {
  0%,
  100% { transform: translateY(0); }
  50% { transform: translateY(-10px); }
}

@keyframes floatB {
  0%,
  100% { transform: rotate(-7deg) translateY(0); }
  50% { transform: rotate(-5deg) translateY(-14px); }
}

@keyframes floatC {
  0%,
  100% { transform: translateY(0); }
  50% { transform: translateY(-9px); }
}

@keyframes blink {
  0%,
  92%,
  100% { transform: scaleY(1); }
  96% { transform: scaleY(0.12); }
}

@keyframes smile {
  0%,
  100% { transform: scaleX(1); }
  50% { transform: scaleX(0.78); }
}

@keyframes sparklePop {
  0%,
  100% { opacity: 0.34; transform: scale(0.72) rotate(0deg); }
  50% { opacity: 1; transform: scale(1) rotate(90deg); }
}

@media (max-width: 980px) {
  .register {
    grid-template-columns: 1fr;
  }

  .register-story {
    min-height: 320px;
    padding: 26px;
  }

  .story-copy {
    display: none;
  }

  .join-stage {
    width: min(420px, 86vw);
  }

  .register-panel {
    min-height: auto;
    padding: 34px 26px 72px;
    border-radius: 8px 8px 0 0;
  }
}

@media (max-width: 520px) {
  .register-story {
    min-height: 290px;
    padding: 20px 22px 16px;
  }

  .story-brand {
    font-size: 18px;
  }

  .join-stage {
    width: min(292px, 82vw);
  }

  .form-title p {
    font-size: 26px;
  }

  .captcha-row {
    grid-template-columns: minmax(0, 1fr) 118px;
    gap: 12px;
  }
}
</style>

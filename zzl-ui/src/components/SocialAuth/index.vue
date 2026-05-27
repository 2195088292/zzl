<template>
  <div class="social-auth">
    <div class="social-divider">
      <span>{{ mode === 'register' ? '也可以用这些账号快速注册' : '也可以用这些账号登录' }}</span>
    </div>
    <div class="social-grid">
      <button
        v-for="provider in socialProviders"
        :key="provider.key"
        class="social-button"
        type="button"
        :style="{ '--provider-color': provider.tone }"
        :aria-label="`${provider.name}${mode === 'register' ? '注册' : '登录'}`"
        @click="handleProvider(provider.key)"
      >
        <span class="social-mark">{{ provider.shortName }}</span>
        <span>{{ provider.name }}</span>
      </button>
    </div>
  </div>
</template>

<script setup>
import { socialProviders, startSocialAuth } from '@/utils/socialAuth'

const props = defineProps({
  mode: {
    type: String,
    default: 'login'
  },
  redirect: {
    type: String,
    default: '/index'
  }
})

function handleProvider(provider) {
  startSocialAuth(provider, props.mode, props.redirect)
}
</script>

<style lang="scss" scoped>
.social-auth {
  margin-top: 22px;
}

.social-divider {
  position: relative;
  display: flex;
  justify-content: center;
  margin-bottom: 14px;
  color: #aaa3b2;
  font-size: 12px;
  font-weight: 800;

  &::before {
    content: "";
    position: absolute;
    top: 50%;
    left: 0;
    right: 0;
    height: 1px;
    background: #ece7f3;
  }

  span {
    position: relative;
    z-index: 1;
    padding: 0 12px;
    background: #fffefb;
  }
}

.social-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.social-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-width: 0;
  height: 42px;
  border: 1px solid #ece7f3;
  border-radius: 8px;
  background: #ffffff;
  color: #17151f;
  font-size: 13px;
  font-weight: 900;
  cursor: pointer;
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;

  &:hover {
    transform: translateY(-2px) rotate(-0.5deg);
    border-color: color-mix(in srgb, var(--provider-color) 48%, #ffffff 52%);
    box-shadow: 0 12px 22px color-mix(in srgb, var(--provider-color) 18%, transparent);
  }
}

.social-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 23px;
  height: 23px;
  border-radius: 7px;
  background: color-mix(in srgb, var(--provider-color) 12%, #ffffff 88%);
  color: var(--provider-color);
  font-size: 12px;
  font-weight: 950;
}

@media (max-width: 520px) {
  .social-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>

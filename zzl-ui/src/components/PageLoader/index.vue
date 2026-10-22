<template>
  <transition name="page-loader-fade">
    <div v-if="pageLoading" class="page-loader" role="status" aria-live="polite">
      <div class="loader-3d" aria-hidden="true">
        <div class="loader-glow"></div>
        <div class="loader-core"></div>
        <div class="loader-ring ring-a"></div>
        <div class="loader-ring ring-b"></div>
        <span class="loader-satellite satellite-a"></span>
        <span class="loader-satellite satellite-b"></span>
      </div>
      <span class="sr-only">正在载入页面</span>
    </div>
  </transition>
</template>

<script setup>
import { pageLoading } from '@/utils/pageLoader'
</script>

<style lang="scss" scoped>
.page-loader {
  position: fixed;
  inset: 0;
  z-index: 9999;
  display: grid;
  place-items: center;
  background: rgba(248, 250, 252, 0.8);
  backdrop-filter: blur(10px);
  pointer-events: none;
}

.loader-3d {
  position: relative;
  width: 150px;
  height: 150px;
  perspective: 680px;
  transform-style: preserve-3d;
  animation: loader-float 3.2s ease-in-out infinite;
}

.loader-glow {
  position: absolute;
  inset: 34px;
  border-radius: 50%;
  background: rgba(99, 102, 241, 0.22);
  filter: blur(18px);
  transform: translateZ(-20px);
}

.loader-core {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 42px;
  height: 42px;
  border: 1px solid rgba(255, 255, 255, 0.72);
  border-radius: 42% 58% 55% 45%;
  background: radial-gradient(circle at 30% 24%, #ffffff 0 9%, #c7d2fe 25%, #818cf8 58%, #4338ca 100%);
  box-shadow: 0 18px 36px rgba(67, 56, 202, 0.28), inset -7px -9px 12px rgba(30, 27, 75, 0.18);
  transform: translate(-50%, -50%) translateZ(22px) rotate(-12deg);
  animation: core-breathe 2.4s ease-in-out infinite;
}

.loader-ring {
  position: absolute;
  inset: 22px;
  border: 1px solid rgba(99, 102, 241, 0.42);
  border-radius: 50%;
  transform-style: preserve-3d;
}

.ring-a {
  transform: rotateX(66deg) rotateZ(12deg);
  animation: ring-a-spin 2.8s linear infinite;
}

.ring-b {
  inset: 32px;
  border-color: rgba(14, 165, 233, 0.38);
  transform: rotateY(68deg) rotateZ(-18deg);
  animation: ring-b-spin 2.2s linear infinite reverse;
}

.loader-satellite {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 9px;
  height: 9px;
  margin: -4.5px;
  border: 1px solid rgba(255, 255, 255, 0.82);
  border-radius: 50%;
  box-shadow: 0 4px 12px rgba(14, 165, 233, 0.3);
  transform-style: preserve-3d;
}

.satellite-a {
  background: #38bdf8;
  animation: satellite-a-spin 2.8s linear infinite;
}

.satellite-b {
  background: #fbbf24;
  animation: satellite-b-spin 2.2s linear infinite reverse;
}

.page-loader-fade-enter-active,
.page-loader-fade-leave-active {
  transition: opacity 0.24s ease;
}

.page-loader-fade-enter-from,
.page-loader-fade-leave-to {
  opacity: 0;
}

@keyframes loader-float {
  0%,
  100% { transform: translateY(0) rotateX(0deg); }
  50% { transform: translateY(-5px) rotateX(4deg); }
}

@keyframes core-breathe {
  0%,
  100% { transform: translate(-50%, -50%) translateZ(22px) rotate(-12deg) scale(0.96); }
  50% { transform: translate(-50%, -50%) translateZ(32px) rotate(8deg) scale(1.06); }
}

@keyframes ring-a-spin {
  from { transform: rotateX(66deg) rotateZ(12deg); }
  to { transform: rotateX(66deg) rotateZ(372deg); }
}

@keyframes ring-b-spin {
  from { transform: rotateY(68deg) rotateZ(-18deg); }
  to { transform: rotateY(68deg) rotateZ(-378deg); }
}

@keyframes satellite-a-spin {
  from { transform: rotateZ(0deg) translateX(52px) rotateZ(0deg); }
  to { transform: rotateZ(360deg) translateX(52px) rotateZ(-360deg); }
}

@keyframes satellite-b-spin {
  from { transform: rotateY(0deg) translateX(43px) rotateY(0deg); }
  to { transform: rotateY(360deg) translateX(43px) rotateY(-360deg); }
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

@media (prefers-reduced-motion: reduce) {
  .loader-3d,
  .loader-core,
  .loader-ring,
  .loader-satellite {
    animation: none;
  }
}
</style>

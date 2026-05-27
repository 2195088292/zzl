<template>
  <transition name="page-loader-fade">
    <div v-if="pageLoading" class="page-loader" role="status" aria-live="polite">
      <div class="loader-scene" aria-hidden="true">
        <div class="loader-orbit orbit-a"></div>
        <div class="loader-orbit orbit-b"></div>
        <div class="loader-cube">
          <span class="cube-face face-front">Z</span>
          <span class="cube-face face-back">Z</span>
          <span class="cube-face face-right"></span>
          <span class="cube-face face-left"></span>
          <span class="cube-face face-top"></span>
          <span class="cube-face face-bottom"></span>
        </div>
        <span class="loader-dot dot-a"></span>
        <span class="loader-dot dot-b"></span>
        <span class="loader-dot dot-c"></span>
      </div>
      <p>Loading Zzl...</p>
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
  gap: 18px;
  padding: 28px;
  overflow: hidden;
  color: #17151f;
  background:
    radial-gradient(circle at 24% 24%, rgba(255, 156, 93, 0.22), transparent 28%),
    radial-gradient(circle at 76% 68%, rgba(109, 77, 247, 0.2), transparent 30%),
    linear-gradient(90deg, rgba(109, 77, 247, 0.08) 1px, transparent 1px),
    linear-gradient(0deg, rgba(109, 77, 247, 0.08) 1px, transparent 1px),
    #fffefb;
  background-size: auto, auto, 54px 54px, 54px 54px, auto;
  pointer-events: none;

  p {
    margin: 172px 0 0;
    font-size: 13px;
    font-weight: 950;
    letter-spacing: 0;
  }
}

.loader-scene {
  position: absolute;
  width: 168px;
  height: 168px;
  perspective: 760px;
  transform-style: preserve-3d;
}

.loader-cube {
  position: absolute;
  inset: 48px;
  transform-style: preserve-3d;
  animation: loaderCubeSpin 1.65s cubic-bezier(0.55, 0, 0.24, 1) infinite;
}

.cube-face {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  border: 2px solid rgba(23, 21, 31, 0.08);
  border-radius: 8px;
  color: #ffffff;
  font-size: 24px;
  font-weight: 950;
  background: linear-gradient(135deg, #6d4df7, #ff9c5d);
  box-shadow: 0 18px 42px rgba(109, 77, 247, 0.2);
}

.face-front { transform: translateZ(36px); }
.face-back { transform: rotateY(180deg) translateZ(36px); }
.face-right { transform: rotateY(90deg) translateZ(36px); background: #f7de3f; }
.face-left { transform: rotateY(-90deg) translateZ(36px); background: #ff9c5d; }
.face-top { transform: rotateX(90deg) translateZ(36px); background: #6d4df7; }
.face-bottom { transform: rotateX(-90deg) translateZ(36px); background: #17151f; }

.loader-orbit {
  position: absolute;
  inset: 18px;
  border: 2px solid rgba(109, 77, 247, 0.18);
  border-radius: 50%;
  transform-style: preserve-3d;
}

.orbit-a {
  animation: orbitA 1.5s linear infinite;
}

.orbit-b {
  inset: 28px;
  border-color: rgba(255, 156, 93, 0.28);
  animation: orbitB 1.9s linear infinite;
}

.loader-dot {
  position: absolute;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  box-shadow: 0 10px 22px rgba(23, 21, 31, 0.12);
}

.dot-a {
  left: 16px;
  top: 54px;
  background: #6d4df7;
  animation: dotFloat 1.2s ease-in-out infinite;
}

.dot-b {
  right: 18px;
  top: 76px;
  background: #ff9c5d;
  animation: dotFloat 1.4s ease-in-out 0.18s infinite;
}

.dot-c {
  left: 74px;
  bottom: 12px;
  background: #f7de3f;
  animation: dotFloat 1.55s ease-in-out 0.32s infinite;
}

.page-loader-fade-enter-active,
.page-loader-fade-leave-active {
  transition: opacity 0.24s ease, transform 0.24s ease;
}

.page-loader-fade-enter-from,
.page-loader-fade-leave-to {
  opacity: 0;
  transform: scale(1.01);
}

@keyframes loaderCubeSpin {
  0% { transform: rotateX(-18deg) rotateY(0deg) rotateZ(0deg); }
  50% { transform: rotateX(18deg) rotateY(180deg) rotateZ(8deg); }
  100% { transform: rotateX(-18deg) rotateY(360deg) rotateZ(0deg); }
}

@keyframes orbitA {
  from { transform: rotateX(64deg) rotateZ(0deg); }
  to { transform: rotateX(64deg) rotateZ(360deg); }
}

@keyframes orbitB {
  from { transform: rotateY(62deg) rotateZ(0deg); }
  to { transform: rotateY(62deg) rotateZ(-360deg); }
}

@keyframes dotFloat {
  0%,
  100% { transform: translateY(0) scale(1); }
  50% { transform: translateY(-10px) scale(1.12); }
}

@media (prefers-reduced-motion: reduce) {
  .loader-cube,
  .loader-orbit,
  .loader-dot {
    animation: none !important;
  }
}
</style>

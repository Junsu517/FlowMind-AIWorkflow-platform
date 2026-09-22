<template>
  <div class="auth">
    <!-- 登录/注册页光斑更大更明显 -->
    <span class="auth__blob auth__blob--indigo" />
    <span class="auth__blob auth__blob--cyan" />

    <div class="auth__brand">
      <div class="auth__logo">
        <span class="auth__logo-mark">FM</span>
        <span class="auth__logo-text">FlowMind</span>
      </div>
      <p class="auth__slogan">编排你的 AI 工作流</p>
    </div>

    <div class="auth__card fm-glass">
      <h2 class="auth__title">{{ title }}</h2>
      <p class="auth__subtitle">{{ subtitle }}</p>
      <slot />
      <div v-if="$slots.footer" class="auth__footer">
        <slot name="footer" />
      </div>
    </div>

    <p class="auth__foot">FlowMind · 可视化 AI 工作流编排平台</p>
  </div>
</template>

<script setup>
defineProps({
  title: { type: String, default: '' },
  subtitle: { type: String, default: '' }
})
</script>

<style scoped>
.auth {
  position: relative;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 26px;
  padding: 48px 20px;
  overflow: hidden;
}

/* 登录页专属：更大更明显的光斑 */
.auth__blob {
  position: absolute;
  z-index: 0;
  border-radius: 50%;
  filter: blur(110px);
  pointer-events: none;
}

.auth__blob--indigo {
  width: 760px;
  height: 760px;
  top: -300px;
  left: -220px;
  background: radial-gradient(circle at 50% 50%, var(--accent-1), transparent 70%);
  opacity: 0.32;
  animation: blob-drift 18s var(--ease) infinite alternate;
}

.auth__blob--cyan {
  width: 680px;
  height: 680px;
  right: -200px;
  bottom: -280px;
  background: radial-gradient(circle at 50% 50%, var(--accent-2), transparent 70%);
  opacity: 0.22;
  animation: blob-drift 22s var(--ease) infinite alternate-reverse;
}

@keyframes blob-drift {
  from {
    transform: translate3d(0, 0, 0) scale(1);
  }
  to {
    transform: translate3d(40px, 30px, 0) scale(1.08);
  }
}

/* ---------- 品牌区 ---------- */
.auth__brand {
  position: relative;
  z-index: 1;
  text-align: center;
}

.auth__logo {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.auth__logo-mark {
  width: 42px;
  height: 42px;
  border-radius: var(--radius-md);
  display: grid;
  place-items: center;
  font-weight: 700;
  font-size: 15px;
  letter-spacing: 0.5px;
  color: var(--on-brand);
  background: var(--gradient-brand);
  box-shadow: var(--shadow-glow);
}

.auth__logo-text {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.5px;
  background: var(--gradient-brand);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}

.auth__slogan {
  margin: 0;
  font-size: 14px;
  letter-spacing: 0.08em;
  color: var(--text-secondary);
}

/* ---------- 玻璃卡片 ---------- */
.auth__card {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 420px;
  padding: 34px 32px 28px;
  background: var(--surface-glass-strong);
  border: 1px solid var(--border-glass);
  box-shadow: var(--shadow-card), 0 0 60px var(--accent-glow-soft);
}

.auth__title {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
}

.auth__subtitle {
  margin: 0 0 26px;
  font-size: 13px;
  color: var(--text-secondary);
}

.auth__footer {
  margin-top: 22px;
  padding-top: 18px;
  border-top: 1px solid var(--border-glass);
  font-size: 13px;
  color: var(--text-secondary);
  text-align: center;
}

.auth__foot {
  position: relative;
  z-index: 1;
  margin: 0;
  font-size: 12px;
  color: var(--text-faint);
}

@media (max-width: 480px) {
  .auth__card {
    padding: 28px 22px 24px;
  }
}
</style>

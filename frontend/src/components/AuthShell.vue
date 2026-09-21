<template>
  <div class="auth">
    <aside class="auth__side">
      <div class="auth__logo">
        <span class="auth__logo-mark">FM</span>
        <span class="auth__logo-text">FlowMind</span>
      </div>

      <h1 class="auth__slogan">把想法串成<br />可运行的工作流</h1>
      <p class="auth__desc">
        拖拽式节点编排，接入大模型与工具，一键执行并实时查看每个节点的输出。
      </p>

      <ul class="auth__features">
        <li><i class="dot dot--blue" />可视化编排 input / llm / condition / http 节点</li>
        <li><i class="dot dot--purple" />支持 <code v-pre>{{变量}}</code> 模板在节点间传递数据</li>
        <li><i class="dot dot--green" />执行过程 SSE 流式回传，逐节点可追溯</li>
      </ul>

      <div class="auth__foot">FlowMind · 阶梯式工作流编排平台</div>
    </aside>

    <main class="auth__main">
      <div class="auth__card fm-glass">
        <h2 class="auth__title">{{ title }}</h2>
        <p class="auth__subtitle">{{ subtitle }}</p>
        <slot />
        <div v-if="$slots.footer" class="auth__footer">
          <slot name="footer" />
        </div>
      </div>
    </main>
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
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1.05fr 1fr;
  align-items: center;
  gap: 40px;
  padding: 48px 6vw;
}

.auth__side {
  max-width: 520px;
}

.auth__logo {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 48px;
}

.auth__logo-mark {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  font-weight: 700;
  color: #fff;
  letter-spacing: 0.5px;
  background: linear-gradient(135deg, var(--fm-primary), var(--fm-primary-2));
  box-shadow: 0 8px 20px rgba(79, 110, 247, 0.35);
}

.auth__logo-text {
  font-size: 20px;
  font-weight: 700;
  color: var(--fm-text-strong);
  letter-spacing: 0.5px;
}

.auth__slogan {
  font-size: 40px;
  line-height: 1.25;
  margin: 0 0 18px;
  color: var(--fm-text-strong);
  font-weight: 700;
}

.auth__desc {
  font-size: 15px;
  line-height: 1.8;
  color: var(--fm-muted);
  margin: 0 0 28px;
}

.auth__features {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 14px;
}

.auth__features li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: #414a5e;
}

.auth__features code {
  background: rgba(79, 110, 247, 0.1);
  color: var(--fm-primary);
  padding: 1px 6px;
  border-radius: 6px;
  font-size: 13px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
  flex: none;
}

.dot--blue {
  background: #4f6ef7;
}
.dot--purple {
  background: #7b5cf0;
}
.dot--green {
  background: #22c1a4;
}

.auth__foot {
  margin-top: 44px;
  font-size: 12px;
  color: rgba(107, 114, 128, 0.75);
}

.auth__main {
  display: flex;
  justify-content: center;
}

.auth__card {
  width: 100%;
  max-width: 420px;
  padding: 36px 34px 30px;
  background: var(--fm-glass-strong);
  box-shadow: var(--fm-shadow-lg);
}

.auth__title {
  margin: 0 0 6px;
  font-size: 24px;
  font-weight: 700;
  color: var(--fm-text-strong);
}

.auth__subtitle {
  margin: 0 0 26px;
  font-size: 13px;
  color: var(--fm-muted);
}

.auth__footer {
  margin-top: 20px;
  padding-top: 18px;
  border-top: 1px solid var(--fm-line);
  font-size: 13px;
  color: var(--fm-muted);
  text-align: center;
}

@media (max-width: 900px) {
  .auth {
    grid-template-columns: 1fr;
    padding: 32px 20px;
  }
  .auth__side {
    display: none;
  }
}
</style>

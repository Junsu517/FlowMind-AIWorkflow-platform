<template>
  <!--
    append-to-body 必须加：本组件挂在 AppHeader（fm-glass，带 backdrop-filter）里。
    backdrop-filter 会让内部 position:fixed 的遮罩以顶栏为包含块，
    遮罩只剩顶栏那 56px 高、整张卡片被裁掉——必须传送到 body 才能正常全屏居中。
  -->
  <el-dialog
    :model-value="visible"
    width="460px"
    align-center
    append-to-body
    :close-on-click-modal="false"
    :show-close="false"
    class="welcome"
    @update:model-value="(v) => emit('update:visible', v)"
  >
    <div class="welcome__body">
      <div class="welcome__icon">
        <el-icon><Compass /></el-icon>
      </div>

      <h3 class="welcome__title">花 1 分钟认识 FlowMind</h3>
      <p class="welcome__desc">
        跟着高亮提示点 7 下，你就知道怎么用这个工具搭出一个能跑的 AI 流程了。
      </p>

      <ul class="welcome__list">
        <li>工作流是什么，从哪里新建一个</li>
        <li>画布上四种积木各自干什么</li>
        <li>怎么跑起来看效果、怎么变成接口给别人用</li>
      </ul>

      <div class="welcome__foot">
        <el-button class="fm-btn-primary" :icon="Promotion" @click="onStart">开始引导</el-button>
        <el-button text @click="onSkip">跳过，我自己摸索</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { Compass, Promotion } from '@element-plus/icons-vue'

const props = defineProps({
  visible: { type: Boolean, default: false }
})

const emit = defineEmits(['update:visible', 'start', 'skip'])

function onStart() {
  emit('update:visible', false)
  emit('start')
}

function onSkip() {
  emit('update:visible', false)
  emit('skip')
}
</script>

<style scoped>
.welcome__body {
  text-align: center;
  padding: 4px 8px 0;
}

.welcome__icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 14px;
  border-radius: 16px;
  display: grid;
  place-items: center;
  font-size: 26px;
  color: var(--on-brand);
  background: var(--gradient-brand);
  box-shadow: var(--shadow-glow);
}

.welcome__title {
  margin: 0 0 8px;
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
}

.welcome__desc {
  margin: 0 0 16px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text-secondary);
}

.welcome__list {
  margin: 0 auto 20px;
  padding: 14px 16px;
  max-width: 340px;
  text-align: left;
  list-style: none;
  border-radius: var(--radius-md);
  border: 1px solid var(--border-glass);
  background: var(--overlay-1);
}

.welcome__list li {
  position: relative;
  padding-left: 16px;
  font-size: 12.5px;
  line-height: 2;
  color: var(--text-secondary);
}

.welcome__list li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 11px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent-1);
}

.welcome__foot {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding-bottom: 6px;
}
</style>

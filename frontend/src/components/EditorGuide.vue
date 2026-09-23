<template>
  <div class="guide">
    <el-icon class="guide__icon"><Guide /></el-icon>

    <ol class="guide__steps">
      <li v-for="(step, i) in steps" :key="i" class="guide__step">
        <span class="guide__idx">{{ i + 1 }}</span>
        <span>{{ step }}</span>
      </li>
    </ol>

    <el-tooltip content="不再显示" placement="top">
      <el-button text :icon="Close" class="guide__close" @click="emit('close')" />
    </el-tooltip>
  </div>
</template>

<script setup>
import { Close, Guide } from '@element-plus/icons-vue'

const emit = defineEmits(['close'])

const steps = [
  '从左栏把节点拖到画布',
  '拖动节点右侧圆点连线（条件节点每个分支各有一个出口）',
  '点「保存」，再展开底部调试面板点「运行」'
]
</script>

<style scoped>
.guide {
  flex: none;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 9px 18px;
  border-bottom: 1px solid var(--border-glass);
  background: linear-gradient(90deg, var(--accent-soft), transparent 70%);
}

.guide__icon {
  flex: none;
  font-size: 16px;
  color: var(--accent-2);
}

.guide__steps {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 22px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.guide__step {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
  color: var(--text-secondary);
}

.guide__idx {
  width: 18px;
  height: 18px;
  border-radius: 6px;
  display: grid;
  place-items: center;
  font-size: 11px;
  font-weight: 700;
  color: var(--on-brand);
  background: var(--gradient-brand);
}

.guide__close {
  margin-left: auto;
  color: var(--text-faint);
}

.guide__close:hover {
  color: var(--text-primary);
}
</style>

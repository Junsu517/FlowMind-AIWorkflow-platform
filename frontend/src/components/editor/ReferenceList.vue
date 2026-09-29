<template>
  <div v-if="items.length" class="refs">
    <div class="refs__title">
      <el-icon class="refs__icon"><Collection /></el-icon>
      <span>引用来源</span>
      <span class="refs__count">{{ items.length }}</span>
    </div>

    <ul class="refs__list">
      <li v-for="(r, i) in items" :key="`${r.filename}-${i}`" class="refs__item">
        <div class="refs__head">
          <span class="refs__file">{{ r.filename || '未知来源' }}</span>
          <span v-if="scoreOf(r)" class="refs__score fm-mono">{{ scoreOf(r) }}</span>
        </div>
        <p v-if="r.snippet" class="refs__snippet">{{ r.snippet }}</p>
      </li>
    </ul>
  </div>
</template>

<script setup>
import { Collection } from '@element-plus/icons-vue'

/**
 * RAG 引用来源（契约 v1.5）：llm 节点配置 knowledge_base_id 后，
 * output 会附带 references: [{ filename, snippet(≤80字，后端已截断), score }]。
 * 终态渲染，SSE 实时路径不涉及；调试面板与执行历史共用这一个组件。
 */
defineProps({
  items: { type: Array, default: () => [] }
})

function scoreOf(r) {
  const n = Number(r?.score)
  return Number.isFinite(n) ? n.toFixed(2) : ''
}
</script>

<style scoped>
.refs {
  margin-top: 8px;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  border: 1px solid var(--accent-border);
  background: var(--accent-glow-soft);
}

.refs__title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--accent-chip-text);
}

.refs__icon {
  font-size: 13px;
}

.refs__count {
  padding: 0 6px;
  border-radius: 999px;
  font-size: 10.5px;
  color: var(--text-secondary);
  background: var(--overlay-4);
}

.refs__list {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.refs__head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.refs__file {
  min-width: 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.refs__score {
  margin-left: auto;
  flex: none;
  font-size: 10.5px;
  color: var(--text-faint);
}

.refs__snippet {
  margin: 3px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--text-secondary);
}
</style>

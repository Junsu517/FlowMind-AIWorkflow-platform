<template>
  <aside class="palette">
    <div class="palette__head">
      <span class="palette__title">节点</span>
      <span class="palette__hint">拖到画布即可创建</span>
    </div>

    <div
      v-for="t in NODE_TYPES"
      :key="t"
      class="palette__item"
      :class="[`palette__item--${t}`, { 'is-dragging': dragging === t }]"
      draggable="true"
      @dragstart="onDragStart($event, t)"
      @dragend="onDragEnd"
    >
      <span class="palette__badge">{{ NODE_TYPE_META[t].short }}</span>
      <div class="palette__text">
        <div class="palette__name">{{ NODE_TYPE_META[t].label }}</div>
        <div class="palette__desc">{{ PURPOSE[t] }}</div>
      </div>
      <el-icon class="palette__grip"><Rank /></el-icon>
    </div>

    <p class="palette__tip">
      连线：从节点右侧圆点拖到另一个节点左侧圆点。条件节点每个分支各有自己的出口。
    </p>
  </aside>
</template>

<script setup>
import { ref } from 'vue'
import { Rank } from '@element-plus/icons-vue'
import { NODE_TYPES, NODE_TYPE_META } from '@/utils/graphModel'

/**
 * 节点用途说明（新手向）。
 * 文案刻意放在面板本地：graphModel.js 本轮是禁区，只准改 defaultConfig('input')。
 */
const PURPOSE = {
  input: '接收用户填写的参数',
  llm: '调用 AI 生成文本（支持工具调用）',
  http: '调用外部 API（如天气查询）',
  condition: '按条件选择走哪条分支'
}

const emit = defineEmits(['drag-start', 'drag-end'])

const dragging = ref('')

function onDragStart(event, type) {
  dragging.value = type
  // Vue Flow 的 drag-and-drop 约定：把类型放进 dataTransfer
  event.dataTransfer.setData('application/vueflow', type)
  event.dataTransfer.effectAllowed = 'move'
  emit('drag-start', type)
}

function onDragEnd() {
  dragging.value = ''
  emit('drag-end')
}
</script>

<style scoped>
.palette {
  flex: none;
  width: 232px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border-right: 1px solid var(--border-glass);
  background: var(--overlay-1);
  overflow: auto;
}

.palette__head {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-bottom: 4px;
}

.palette__title {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-primary);
}

.palette__hint {
  font-size: 11px;
  color: var(--text-faint);
}

.palette__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border-glass);
  background: var(--bg-glass);
  cursor: grab;
  user-select: none;
  transition: border-color 0.2s var(--ease), background 0.2s var(--ease), transform 0.15s var(--ease);
}

.palette__item:hover {
  border-color: var(--border-glass-strong);
  background: var(--overlay-3);
  transform: translateY(-1px);
}

.palette__item:active,
.palette__item.is-dragging {
  cursor: grabbing;
  border-color: var(--accent-1);
  box-shadow: var(--shadow-glow);
}

.palette__badge {
  flex: none;
  min-width: 30px;
  padding: 3px 6px;
  border-radius: 7px;
  font-size: 10px;
  font-weight: 700;
  text-align: center;
  color: var(--bg-base);
}

.palette__item--input .palette__badge {
  background: var(--node-input);
}
.palette__item--llm .palette__badge {
  background: var(--node-llm);
}
.palette__item--condition .palette__badge {
  background: var(--node-condition);
}
.palette__item--http .palette__badge {
  background: var(--node-http);
}

.palette__text {
  min-width: 0;
  flex: 1;
}

.palette__name {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-primary);
}

.palette__desc {
  margin-top: 2px;
  font-size: 11px;
  line-height: 1.45;
  color: var(--text-faint);
}

.palette__grip {
  font-size: 14px;
  color: var(--text-faint);
}

.palette__tip {
  margin: 8px 0 0;
  padding: 10px;
  border-radius: var(--radius-md);
  border: 1px dashed var(--border-glass);
  font-size: 11px;
  line-height: 1.6;
  color: var(--text-secondary);
}
</style>

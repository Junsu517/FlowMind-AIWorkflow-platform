<template>
  <div class="editor">
    <AppHeader />

    <div class="editor__bar fm-glass">
      <el-button text :icon="ArrowLeft" @click="router.push('/workflows')">返回</el-button>

      <el-input v-model="name" class="editor__name" placeholder="工作流名称" />

      <span class="editor__count fm-mono">{{ nodes.length }} 节点</span>
      <span v-if="dirty" class="editor__dirty">未保存</span>

      <div class="editor__bar-right">
        <el-dropdown trigger="click" @command="applyTemplate">
          <el-button :icon="MagicStick">模板</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="weather">天气助手（input → 工具 → LLM）</el-dropdown-item>
              <el-dropdown-item command="branch">条件分支示例（两条分支）</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <!-- 阶梯 7：定时计划（与画布版同入口） -->
        <el-button :icon="Timer" :class="{ 'has-sched': schedule?.enabled }" @click="scheduleVisible = true">
          定时
          <span v-if="schedule?.enabled" class="sched-dot" />
        </el-button>
        <el-button text :icon="Clock" @click="router.push('/executions')">执行历史</el-button>
        <el-button class="fm-btn-primary" :icon="Select" :loading="saving" @click="save">保存</el-button>
      </div>
    </div>

    <div v-loading="loading" class="fm-page editor__body">
      <p v-if="loadError" class="editor__error">{{ loadError }}</p>

      <template v-else-if="!loading">
        <!-- 节点卡片列表：按执行顺序从上到下 -->
        <div class="node-list">
          <NodeCard
            v-for="(node, i) in nodes"
            :key="node.uid"
            :node="node"
            :index="i"
            :total="nodes.length"
            :nodes="nodes"
            :tools="tools"
            :tools-loading="toolsLoading"
            :knowledge-bases="knowledgeBases"
            :knowledge-loading="knowledgeLoading"
            @remove="removeNode(i)"
            @move-up="move(i, -1)"
            @move-down="move(i, 1)"
          />

          <div class="add-node fm-glass">
            <span class="add-node__label">添加节点</span>
            <el-button v-for="t in NODE_TYPES" :key="t" size="small" :icon="Plus" @click="addNode(t)">
              {{ NODE_TYPE_META[t].label }}
            </el-button>
          </div>
        </div>

        <!-- 调试面板 -->
        <DebugPanel ref="debugRef" :workflow-id="id" :nodes="nodes" />
      </template>
    </div>

    <SchedulePanel v-model:visible="scheduleVisible" :workflow-id="id" @saved="onScheduleSaved" />
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Plus, Select, MagicStick, Clock, Timer } from '@element-plus/icons-vue'
import AppHeader from '@/components/AppHeader.vue'
import NodeCard from '@/components/editor/NodeCard.vue'
import DebugPanel from '@/components/editor/DebugPanel.vue'
import { getWorkflow, updateWorkflow } from '@/api/workflow'
import { getGraph, saveGraph } from '@/api/graph'
import { getTools } from '@/api/tools'
import { getKnowledgeBases } from '@/api/knowledge'
import { getSchedule } from '@/api/schedule'
import SchedulePanel from '@/components/SchedulePanel.vue'
import {
  NODE_TYPES,
  NODE_TYPE_META,
  createNode,
  graphToForm,
  formToGraph,
  weatherTemplate,
  branchTemplate
} from '@/utils/graphModel'

const route = useRoute()
const router = useRouter()

// 同一个编辑器路由切换工作流 id 时组件会被复用，必须按 id 变化重新加载
const id = computed(() => route.params.id)
const name = ref('')
const originName = ref('')
const nodes = ref([])
const tools = ref([])
const toolsLoading = ref(false)
const knowledgeBases = ref([])
const knowledgeLoading = ref(false)
/** 阶梯 7：定时计划（null = 没有计划） */
const schedule = ref(null)
const scheduleVisible = ref(false)
const loading = ref(true)
const saving = ref(false)
const dirty = ref(false)
const loadError = ref('')
const debugRef = ref(null)

// 首次加载完成前不标记「未保存」，否则深度 watch 会在赋值后补触发一次
const ready = ref(false)

watch(nodes, () => {
  if (ready.value) dirty.value = true
}, { deep: true })

watch(name, () => {
  if (ready.value) dirty.value = true
})

/**
 * 落库前的最后一道规范化（视图层做，避开 graphModel 的契约转换函数禁区）：
 * - input 字段显示名没填时用参数名兜底
 * - knowledge_base_id 为空则移除字段，保证后端未配该字段时行为不变
 */
function normalizeNodesForSave(nodes) {
  for (const n of nodes) {
    if (n.type === 'input') {
      n.config.fields = (n.config.fields || []).map((f) => ({ ...f, label: f.label || f.key }))
    }
    if (n.type === 'llm' && n.config.knowledge_base_id == null) {
      delete n.config.knowledge_base_id
    }
  }
}

/** 保存前的本地校验，尽量把 INVALID_GRAPH 挡在前端 */
function localValidate() {
  const list = nodes.value
  if (!list.length) return '至少需要添加一个节点'
  if (list.filter((n) => n.type === 'input').length !== 1) return '图中必须且只能有一个 input 节点'

  const keys = list.map((n) => n.nodeKey)
  if (keys.some((k) => !k)) return '存在空的节点 key'
  if (new Set(keys).size !== keys.length) return '节点 key 必须唯一'

  for (const n of list) {
    if (n.type !== 'input' && !(n.sources || []).some((s) => s.from)) {
      return `节点「${n.name}」还没有配置上游来源`
    }
    if (n.type === 'http' && n.config.tool_id == null) {
      return `节点「${n.name}」还没有选择工具`
    }
    if (n.type === 'input') {
      const fields = n.config.fields || []
      if (!fields.length) return 'input 节点至少需要一个入参字段'
      if (fields.some((f) => !String(f.key || '').trim())) {
        return `节点「${n.name}」的入参字段还没填参数名称（key）`
      }
      if (new Set(fields.map((f) => f.key)).size !== fields.length) return 'input 字段 key 必须唯一'
    }
    if (n.type === 'llm' && !String(n.config.prompt || '').trim()) {
      return `节点「${n.name}」的 prompt 不能为空`
    }
    if (n.type === 'condition') {
      const rules = n.config.rules || []
      if (!rules.length) return `节点「${n.name}」至少配置一条分支规则`
      if (rules.some((r) => !r.handle)) return '每条分支规则都需要填写 handle'
      if (!n.config.default_handle) return `节点「${n.name}」需要填写默认分支 handle`
    }
  }
  return ''
}

async function save() {
  const err = localValidate()
  if (err) {
    ElMessage.warning(err)
    return
  }
  saving.value = true
  try {
    const trimmed = String(name.value || '').trim()
    if (trimmed && trimmed !== originName.value) {
      const wf = await updateWorkflow(id.value, { name: trimmed })
      originName.value = wf.name ?? trimmed
      name.value = originName.value
    }
    const graph = formToGraph(nodes.value)
    normalizeNodesForSave(graph.nodes)
    await saveGraph(id.value, graph)
    dirty.value = false
    ElMessage.success('图已保存')
  } catch (e) {
    // 具体错误（含 INVALID_GRAPH 的 message）由 axios 拦截器统一弹出
  } finally {
    saving.value = false
  }
}

function addNode(type) {
  nodes.value.push(createNode(type, nodes.value))
}

function removeNode(i) {
  nodes.value.splice(i, 1)
}

function move(i, delta) {
  const target = i + delta
  if (target < 0 || target >= nodes.value.length) return
  const list = nodes.value
  const tmp = list[i]
  list[i] = list[target]
  list[target] = tmp
}

async function applyTemplate(cmd) {
  const tpl = cmd === 'branch' ? branchTemplate() : weatherTemplate()
  if (nodes.value.length) {
    try {
      await ElMessageBox.confirm('应用模板会替换当前所有节点，是否继续？', '提示', {
        type: 'warning',
        confirmButtonText: '替换',
        cancelButtonText: '取消'
      })
    } catch {
      return
    }
  }
  nodes.value = tpl
  dirty.value = true
  ElMessage.info('模板已载入，记得点「保存」')
}

async function loadTools() {
  toolsLoading.value = true
  try {
    const data = await getTools()
    tools.value = data.items || []
  } catch (e) {
    tools.value = []
  } finally {
    toolsLoading.value = false
  }
}

async function loadSchedule() {
  try {
    schedule.value = await getSchedule(id.value)
  } catch (e) {
    schedule.value = null
  }
}

function onScheduleSaved(saved) {
  schedule.value = saved || null
}

async function loadKnowledge() {
  knowledgeLoading.value = true
  try {
    const data = await getKnowledgeBases({ page: 1, size: 50 })
    knowledgeBases.value = data.items || []
  } catch (e) {
    knowledgeBases.value = []
  } finally {
    knowledgeLoading.value = false
  }
}

async function loadAll() {
  loading.value = true
  loadError.value = ''
  ready.value = false
  try {
    const [wf, graph] = await Promise.all([getWorkflow(id.value), getGraph(id.value).catch(() => null)])
    name.value = wf.name
    originName.value = wf.name
    const list = graphToForm(graph)
    // 新工作流的图是空的，直接给一个可运行的示例，降低演示门槛
    const emptyGraph = !list.length
    nodes.value = emptyGraph ? weatherTemplate() : list
    nextTick(() => {
      ready.value = true
      dirty.value = emptyGraph
    })
  } catch (e) {
    loadError.value = '加载工作流失败，请返回列表重试。'
    ready.value = true
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadTools(), loadKnowledge(), loadSchedule()])
  loadAll()
})

watch(id, (next, prev) => {
  if (next && next !== prev) loadAll()
})
</script>

<style scoped>
.editor {
  min-height: 100%;
}

.editor__bar {
  position: sticky;
  top: 57px;
  z-index: 15;
  display: flex;
  align-items: center;
  gap: 12px;
  max-width: 1180px;
  margin: 14px auto 0;
  padding: 12px 18px;
  border-radius: var(--radius-lg);
}

.editor__name {
  width: 280px;
}

.editor__count {
  font-size: 12px;
  color: var(--text-faint);
}

.editor__dirty {
  font-size: 11.5px;
  color: var(--warn);
  background: var(--warn-soft);
  border: 1px solid var(--warn-border);
  border-radius: 999px;
  padding: 2px 10px;
}

.editor__bar-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 已启用定时的按钮：绿色小点提示"这个工作流会自己跑" */
.has-sched {
  border-color: var(--accent-border);
  color: var(--text-primary);
}

.sched-dot {
  width: 6px;
  height: 6px;
  margin-left: 6px;
  border-radius: 50%;
  background: var(--status-success);
  box-shadow: 0 0 6px var(--status-success);
}

.editor__body {
  padding-top: 20px;
}

.editor__error {
  padding: 40px 0;
  text-align: center;
  color: var(--text-secondary);
}

.node-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.add-node {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 18px;
  background: var(--overlay-1);
  border-style: dashed;
}

.add-node__label {
  font-size: 13px;
  color: var(--text-secondary);
  margin-right: 4px;
}

.debug-wrapper {
  margin-top: 18px;
}

@media (max-width: 900px) {
  .editor__bar {
    flex-wrap: wrap;
  }
  .editor__name {
    width: 100%;
  }
}
</style>

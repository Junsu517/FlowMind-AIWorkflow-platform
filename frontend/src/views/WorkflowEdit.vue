<template>
  <!-- 旧表单式编辑器：按任务卡要求先保留，通过 ?mode=form 仍可访问 -->
  <div v-if="formMode" class="form-mode">
    <WorkflowEditForm />
    <el-button class="form-mode__back" :icon="GridIcon" @click="backToCanvas">返回画布版</el-button>
  </div>

  <div v-else class="canvas-editor">
    <AppHeader />

    <div class="editor__bar">
      <el-button text :icon="ArrowLeft" @click="router.push('/workflows')">返回</el-button>

      <el-input v-model="name" class="editor__name" placeholder="工作流名称" />

      <span class="editor__count fm-mono">{{ flowNodes.length }} 节点 · {{ flowEdges.length }} 连线</span>
      <span v-if="dirty" class="editor__dirty">未保存</span>

      <div class="editor__bar-right">
        <el-dropdown trigger="click" @command="applyTemplate">
          <el-button class="tpl-btn" data-tour="template" :icon="MagicStick">模板</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="weather">天气助手（输入 → 工具 → LLM）</el-dropdown-item>
              <el-dropdown-item command="branch">条件分支示例（两条分支）</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-tooltip content="切换到旧版表单编辑器（保留中）" placement="bottom">
          <el-button text :icon="ListIcon" @click="switchToForm">表单版</el-button>
        </el-tooltip>
        <!-- 阶梯 7：定时计划（契约 v1.7） -->
        <el-button :icon="Timer" :class="{ 'has-sched': schedule?.enabled }" @click="scheduleVisible = true">
          定时
          <span v-if="schedule?.enabled" class="sched-dot" />
        </el-button>
        <!-- 阶梯 8：发布为 HTTP API（契约 v1.8） -->
        <el-button
          data-tour="publish"
          :icon="Promotion"
          :class="{ 'has-sched': publishInfo }"
          @click="publishVisible = true"
        >
          发布
          <span v-if="publishInfo" class="pub-dot" />
        </el-button>
        <el-button text :icon="Clock" @click="router.push('/executions')">执行历史</el-button>
        <!-- 删除入口不能只依赖键盘：选中节点或连线后这里出现 -->
        <el-button v-if="hasSelection" :icon="DeleteIcon" @click="deleteSelected">删除所选</el-button>
        <el-button class="fm-btn-primary" :icon="Select" :loading="saving" @click="save">保存</el-button>
      </div>
    </div>

    <!-- 首次进入编辑器的步骤引导（关掉后不再出现） -->
    <EditorGuide v-if="showGuide" @close="dismissGuide" />

    <div v-loading="loading" class="editor__main">
      <NodePalette @drag-start="onPaletteDragStart" @drag-end="onPaletteDragEnd" />

      <div
        class="canvas"
        data-tour="canvas"
        :class="{ 'is-drop-target': dropActive, 'is-connecting': connecting }"
        @dragover.prevent="onDragOver"
        @dragleave="onDragLeave"
        @drop="onDrop"
      >
        <VueFlow
          :fit-view-on-init="true"
          :min-zoom="0.3"
          :max-zoom="2"
          :default-viewport="{ x: 0, y: 0, zoom: 0.9 }"
          :delete-key-code="['Delete', 'Backspace']"
          :connect-on-click="false"
          :elevate-edges-on-select="true"
          :connection-mode="ConnectionMode.Loose"
          :connection-radius="36"
          :default-edge-options="{ type: 'smoothstep' }"
          :connection-line-type="'smoothstep'"
        >
          <template #node-fm-input="props"><InputNode v-bind="props" /></template>
          <template #node-fm-llm="props"><LlmNode v-bind="props" /></template>
          <template #node-fm-condition="props"><ConditionNode v-bind="props" /></template>
          <template #node-fm-http="props"><HttpNode v-bind="props" /></template>

          <Background :gap="22" :size="1.6" pattern-color="rgba(139,147,167,0.35)" />
          <Controls position="bottom-left" :show-interactive="false" />
        </VueFlow>

        <div v-if="!flowNodes.length" class="canvas__empty">
          <div class="canvas__empty-icon">
            <el-icon><MagicStick /></el-icon>
          </div>
          <p class="canvas__empty-title">从左侧拖入节点开始搭建</p>
          <p class="canvas__empty-desc">输入 → 工具/条件 → LLM，拖动节点右侧圆点即可连线</p>
        </div>
      </div>

      <NodeConfigPanel
        :node="selectedNode"
        :tools="tools"
        :tools-loading="toolsLoading"
        :knowledge-bases="knowledgeBases"
        :knowledge-loading="knowledgeLoading"
        :ref-options="refOptions"
        @delete="removeSelected"
      />
    </div>

    <!-- 调试面板：收起时只留标题条，SSE 逻辑完全复用 DebugPanel -->
    <div class="dock" :class="{ 'is-open': debugOpen }">
      <div class="dock__bar" data-tour="run" @click="debugOpen = !debugOpen">
        <el-icon class="dock__icon"><VideoPlay /></el-icon>
        <span class="dock__title">调试面板</span>
        <span v-if="runState" class="dock__state">{{ runState }}</span>
        <el-icon class="dock__arrow"><ArrowDown /></el-icon>
      </div>
      <div v-show="debugOpen" class="dock__body">
        <DebugPanel ref="debugRef" :workflow-id="id" :nodes="debugNodes" @node-status="onNodeStatus" />
      </div>
    </div>

    <SchedulePanel v-model:visible="scheduleVisible" :workflow-id="id" @saved="onScheduleSaved" />
    <PublishPanel
      v-model:visible="publishVisible"
      :workflow-id="id"
      :nodes="debugNodes"
      @changed="onPublishChanged"
    />
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, ArrowDown, Select, MagicStick, Clock, VideoPlay } from '@element-plus/icons-vue'
import { Delete as DeleteIcon, Timer, Promotion } from '@element-plus/icons-vue'
import SchedulePanel from '@/components/SchedulePanel.vue'
import PublishPanel from '@/components/PublishPanel.vue'
import { getSchedule } from '@/api/schedule'
import { getPublish } from '@/api/publish'
import { Grid as GridIcon, List as ListIcon } from '@element-plus/icons-vue'
import { VueFlow, useVueFlow, ConnectionMode } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/controls/dist/style.css'

import AppHeader from '@/components/AppHeader.vue'
import EditorGuide from '@/components/EditorGuide.vue'
import NodePalette from '@/components/canvas/NodePalette.vue'
import NodeConfigPanel from '@/components/canvas/NodeConfigPanel.vue'
import InputNode from '@/components/canvas/nodes/InputNode.vue'
import LlmNode from '@/components/canvas/nodes/LlmNode.vue'
import ConditionNode from '@/components/canvas/nodes/ConditionNode.vue'
import HttpNode from '@/components/canvas/nodes/HttpNode.vue'
import DebugPanel from '@/components/editor/DebugPanel.vue'
import WorkflowEditForm from '@/views/WorkflowEditForm.vue'

import { getWorkflow, updateWorkflow } from '@/api/workflow'
import { getGraph, saveGraph } from '@/api/graph'
import { getTools } from '@/api/tools'
import { getKnowledgeBases } from '@/api/knowledge'
import {
  NODE_TYPE_META,
  graphToCanvas,
  canvasToGraph,
  createCanvasNode,
  branchHandles,
  refsFrom,
  weatherCanvasGraph,
  branchCanvasGraph
} from '@/utils/graphModel'

const route = useRoute()
const router = useRouter()

// 同一个编辑器路由切换工作流 id 时组件会被复用，必须按 id 变化重新加载
const id = computed(() => route.params.id)

/** 旧表单版入口：?mode=form */
const formMode = computed(() => route.query.mode === 'form')

const name = ref('')
const originName = ref('')
const tools = ref([])
const toolsLoading = ref(false)
const knowledgeBases = ref([])
const knowledgeLoading = ref(false)
/** 阶梯 7：定时计划（null = 没有计划） */
const schedule = ref(null)
const scheduleVisible = ref(false)
/** 阶梯 8：发布状态（null = 未发布） */
const publishInfo = ref(null)
const publishVisible = ref(false)
const loading = ref(true)
const saving = ref(false)
const dirty = ref(false)
const debugOpen = ref(false)
const runState = ref('')
const selectedId = ref('')
const dropActive = ref(false)
const ready = ref(false)

provide('fm-tools', tools)
provide('fm-knowledge', knowledgeBases)

/** 新手引导：只在首次进入编辑器时出现，localStorage 记录「已看过」 */
const GUIDE_KEY = 'flowmind_editor_guide_v1'
const showGuide = ref(false)

function dismissGuide() {
  showGuide.value = false
  try {
    localStorage.setItem(GUIDE_KEY, '1')
  } catch {
    /* localStorage 不可用时忽略 */
  }
}

const {
  nodes: flowNodes,
  edges: flowEdges,
  addNodes,
  addEdges,
  removeEdges,
  removeNodes,
  setNodes,
  setEdges,
  onConnect,
  onConnectStart,
  onConnectEnd,
  onNodeClick,
  onPaneClick,
  screenToFlowCoordinate,
  fitView
} = useVueFlow()

/**
 * 走线用 smoothstep（小圆角折线）：贝塞尔在两节点靠近时控制点外扩，
 * 线会越过目标节点再折回；smoothstep 不会突入节点内部。
 */
function withEdgeType(edges) {
  return (edges || []).map((e) => ({ ...e, type: 'smoothstep' }))
}

/** 正在拖连线：此时把目标 handle 的热区撑满整个节点（见 global.css 的 .is-connecting） */
const connecting = ref(false)
onConnectStart(() => {
  connecting.value = true
})
onConnectEnd(() => {
  connecting.value = false
})

// ---------- 脏标记 ----------
watch(
  flowNodes,
  () => {
    if (ready.value) dirty.value = true
  },
  { deep: true }
)
watch(
  flowEdges,
  () => {
    if (ready.value) dirty.value = true
  },
  { deep: true }
)
watch(name, () => {
  if (ready.value) dirty.value = true
})

const selectedNode = computed(() => flowNodes.value.find((n) => n.id === selectedId.value) || null)

const refOptions = computed(() =>
  selectedId.value ? refsFrom(selectedId.value, flowNodes.value, flowEdges.value) : []
)

/** DebugPanel 吃的是「表单态节点」数组，这里从画布节点里抽 data 出来，面板逻辑一行不改 */
const debugNodes = computed(() => flowNodes.value.map((n) => n.data))

onNodeClick(({ node }) => {
  selectedId.value = node.id
})
onPaneClick(() => {
  selectedId.value = ''
})

onConnect((conn) => {
  if (!conn.source || !conn.target) return
  if (conn.source === conn.target) {
    ElMessage.warning('不能把节点连到自己')
    return
  }
  const dup = flowEdges.value.some(
    (e) =>
      e.source === conn.source &&
      e.target === conn.target &&
      (e.sourceHandle || null) === (conn.sourceHandle || null)
  )
  if (dup) {
    ElMessage.warning('这条连线已经存在')
    return
  }
  addEdges([
    {
      ...conn,
      id: `e-${conn.source}-${conn.target}-${conn.sourceHandle || 'n'}-${Date.now()}`
    }
  ])
  dirty.value = true
})

// ---------- 左侧面板拖放 ----------
function onPaletteDragStart() {
  dropActive.value = true
}
function onPaletteDragEnd() {
  dropActive.value = false
}
function onDragOver(event) {
  dropActive.value = true
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
}
function onDragLeave(event) {
  if (event.currentTarget && event.relatedTarget && event.currentTarget.contains(event.relatedTarget)) return
  dropActive.value = false
}
function onDrop(event) {
  dropActive.value = false
  const type = event.dataTransfer?.getData('application/vueflow')
  if (!type || !NODE_TYPE_META[type]) return
  const pos = screenToFlowCoordinate({ x: event.clientX, y: event.clientY })
  const node = createCanvasNode(type, flowNodes.value, { x: pos.x - 104, y: pos.y - 44 })
  addNodes([node])
  selectedId.value = node.id
  dirty.value = true
  ElMessage.success(`已添加 ${NODE_TYPE_META[type].label} 节点`)
}

function removeSelected() {
  if (!selectedId.value) return
  removeNodes(selectedId.value)
  selectedId.value = ''
  dirty.value = true
}

/** 画布上有选中的节点或连线（用于显示“删除所选”按钮） */
const hasSelection = computed(
  () => flowNodes.value.some((n) => n.selected) || flowEdges.value.some((e) => e.selected)
)

/** 删除当前选中的连线 / 节点（键盘 Delete/Backspace 走 Vue Flow 自己的处理） */
function deleteSelected() {
  const edgeIds = flowEdges.value.filter((e) => e.selected).map((e) => e.id)
  const nodeIds = flowNodes.value.filter((n) => n.selected).map((n) => n.id)
  if (!edgeIds.length && !nodeIds.length) {
    ElMessage.warning('先点选要删除的节点或连线')
    return
  }
  if (edgeIds.length) removeEdges(edgeIds)
  if (nodeIds.length) {
    removeNodes(nodeIds)
    if (nodeIds.includes(selectedId.value)) selectedId.value = ''
  }
  const parts = []
  if (nodeIds.length) parts.push(`${nodeIds.length} 个节点`)
  if (edgeIds.length) parts.push(`${edgeIds.length} 条连线`)
  ElMessage.success(`已删除 ${parts.join('、')}`)
  dirty.value = true
}

// ---------- 调试状态回写到画布节点 ----------
function onNodeStatus(nodeKey, status) {
  const node = flowNodes.value.find((n) => n.id === nodeKey)
  if (node) node.data.status = status
  const map = { running: '运行中', success: '已完成', failed: '失败', skipped: '已跳过', pending: '' }
  runState.value = map[status] || ''
}

// ---------- 校验与保存 ----------
/** 条件节点的分支改名后，指向旧 handle 的连线要清掉 */
function pruneStaleEdges() {
  const stale = []
  for (const e of flowEdges.value) {
    const src = flowNodes.value.find((n) => n.id === e.source)
    if (!src || src.data.type !== 'condition') continue
    if (!e.sourceHandle) continue
    if (!branchHandles(src.data).includes(e.sourceHandle)) stale.push(e.id)
  }
  if (stale.length) removeEdges(stale)
  return stale.length
}

/**
 * 落库前的最后一道规范化（放在视图层，避开 graphModel 的契约转换函数禁区）：
 * - input 字段的显示名没填时用参数名兜底
 * - knowledge_base_id 为空就整个字段移除，与 tools 一样「按需下发」：
 *   后端没配该字段时行为与现在完全一致
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

function localValidate() {
  const ns = flowNodes.value
  if (!ns.length) return '画布上还没有节点，先从左侧拖入一个'
  const inputs = ns.filter((n) => n.data.type === 'input')
  if (inputs.length !== 1) return '图中必须且只能有一个 input 节点'

  const keys = ns.map((n) => n.data.nodeKey)
  if (keys.some((k) => !k)) return '存在空的节点 key'
  if (new Set(keys).size !== keys.length) return '节点 key 必须唯一'

  for (const n of ns) {
    const { type, config } = n.data
    const inDegree = flowEdges.value.filter((e) => e.target === n.id).length
    if (type === 'input') {
      if (inDegree) return 'input 节点不能有上游连线'
      if (!(config.fields || []).length) return 'input 节点至少需要一个入参字段'
      if (config.fields.some((f) => !String(f.key || '').trim())) {
        return `节点「${n.data.name}」的入参字段还没填参数名称（key）`
      }
      if (new Set(config.fields.map((f) => f.key)).size !== config.fields.length) {
        return 'input 字段 key 必须唯一'
      }
    } else if (!inDegree) {
      return `节点「${n.data.name}」还没有上游连线`
    }

    if (type === 'http' && config.tool_id == null) return `节点「${n.data.name}」还没有选择工具`
    if (type === 'llm' && !String(config.prompt || '').trim()) return `节点「${n.data.name}」的 prompt 不能为空`
    if (type === 'condition') {
      const rules = config.rules || []
      if (!rules.length) return `节点「${n.data.name}」至少配置一条分支规则`
      if (rules.some((r) => !r.handle)) return '每条分支规则都需要填写 handle'
      if (!config.default_handle) return `节点「${n.data.name}」需要填写默认分支 handle`
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
  const pruned = pruneStaleEdges()
  if (pruned) ElMessage.info(`已清理 ${pruned} 条失效的分支连线`)

  saving.value = true
  try {
    const trimmed = String(name.value || '').trim()
    if (trimmed && trimmed !== originName.value) {
      const wf = await updateWorkflow(id.value, { name: trimmed })
      originName.value = wf.name ?? trimmed
      name.value = originName.value
    }
    const graph = canvasToGraph(flowNodes.value, flowEdges.value)
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

async function applyTemplate(cmd) {
  if (flowNodes.value.length) {
    try {
      await ElMessageBox.confirm('应用模板会替换当前画布上的全部节点和连线，是否继续？', '提示', {
        type: 'warning',
        confirmButtonText: '替换',
        cancelButtonText: '取消'
      })
    } catch {
      return
    }
  }
  const graph = cmd === 'branch' ? branchCanvasGraph() : weatherCanvasGraph()
  setNodes(graph.nodes)
  setEdges(withEdgeType(graph.edges))
  selectedId.value = ''
  dirty.value = true
  nextTick(() => fitView({ padding: 0.2 }))
  ElMessage.info('模板已载入，记得点「保存」')
}

function switchToForm() {
  router.push({ path: `/workflows/${id.value}/edit`, query: { mode: 'form' } })
}
function backToCanvas() {
  router.push({ path: `/workflows/${id.value}/edit` })
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

async function loadPublish() {
  try {
    publishInfo.value = await getPublish(id.value)
  } catch (e) {
    publishInfo.value = null
  }
}

function onPublishChanged(published) {
  publishInfo.value = published ? publishInfo.value || { published: true } : null
}

/** 与 tools 同一加载方式：编辑器进入时拉一次知识库列表 */
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
  ready.value = false
  try {
    const [wf, graph] = await Promise.all([getWorkflow(id.value), getGraph(id.value).catch(() => null)])
    name.value = wf.name
    originName.value = wf.name
    const canvas = graphToCanvas(graph)
    // 画布版不再自动套模板：空画布要展示引导态（模板由工具栏按钮一键生成）
    setNodes(canvas.nodes)
    setEdges(withEdgeType(canvas.edges))
    selectedId.value = ''
    nextTick(() => {
      fitView({ padding: 0.2 })
      ready.value = true
      dirty.value = false
    })
  } catch (e) {
    ElMessage.error('加载工作流失败，请返回列表重试。')
    ready.value = true
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    showGuide.value = localStorage.getItem(GUIDE_KEY) !== '1'
  } catch {
    showGuide.value = true
  }
  await Promise.all([loadTools(), loadKnowledge(), loadSchedule(), loadPublish()])
  if (!formMode.value) loadAll()
})

watch(id, (next, prev) => {
  if (next && next !== prev && !formMode.value) loadAll()
})

// 从旧表单版切回画布时，画布容器是重新挂载的，需要重新拉一次图
watch(formMode, (isForm) => {
  if (!isForm) loadAll()
})
</script>

<style scoped>
/* 画布版锁屏布局。类名改 canvas-editor 的原因见模板注释（防串到表单版根节点） */
.canvas-editor {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
}

.editor__bar {
  flex: none;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 18px;
  border-bottom: 1px solid var(--border-glass);
  background: var(--surface-header);
}

.editor__name {
  width: 260px;
}

.editor__count {
  font-size: 12px;
  color: var(--text-faint);
  white-space: nowrap;
}

.editor__dirty {
  font-size: 11.5px;
  color: var(--warn);
  background: var(--warn-soft);
  border: 1px solid var(--warn-border);
  border-radius: 999px;
  padding: 2px 10px;
  white-space: nowrap;
}

.editor__bar-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 已启用定时的按钮：加一个绿色小点，一眼看出"这个工作流会自己跑" */
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

/* 已发布的小点用品牌紫，和绿色的"已定时"区分 */
.pub-dot {
  width: 6px;
  height: 6px;
  margin-left: 6px;
  border-radius: 50%;
  background: var(--accent-2);
  box-shadow: 0 0 6px var(--accent-2-glow);
}

/* 模板按钮醒目化：渐变描边，让新用户第一眼看到"从模板开始"的入口 */
.tpl-btn {
  font-weight: 600;
  color: var(--text-primary);
  border: 1px solid transparent;
  background-image: linear-gradient(var(--surface-solid), var(--surface-solid)), var(--gradient-brand);
  background-origin: border-box;
  background-clip: padding-box, border-box;
  transition: box-shadow 0.2s var(--ease), transform 0.2s var(--ease);
}

.tpl-btn:hover {
  transform: translateY(-1px);
  box-shadow: var(--accent-glow-soft);
  color: var(--text-primary);
}

.editor__main {
  flex: 1;
  min-height: 0;
  display: flex;
}

.canvas {
  position: relative;
  flex: 1;
  min-width: 0;
  background:
    radial-gradient(900px 500px at 12% 0%, rgba(99, 102, 241, 0.08), transparent 70%),
    radial-gradient(800px 500px at 100% 100%, rgba(34, 211, 238, 0.06), transparent 70%);
  transition: box-shadow 0.2s var(--ease);
}

.canvas.is-drop-target {
  box-shadow: inset 0 0 0 2px var(--accent-1), inset 0 0 60px rgba(99, 102, 241, 0.16);
}

.canvas__empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  pointer-events: none;
  text-align: center;
}

.canvas__empty-icon {
  width: 62px;
  height: 62px;
  border-radius: 20px;
  display: grid;
  place-items: center;
  font-size: 28px;
  color: var(--on-brand);
  background: var(--gradient-brand);
  box-shadow: var(--shadow-glow), var(--shadow-pop);
}

.canvas__empty-title {
  margin: 6px 0 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.canvas__empty-desc {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-secondary);
}

/* ---------- 底部调试抽屉 ---------- */
.dock {
  flex: none;
  border-top: 1px solid var(--border-glass);
  background: var(--surface-header);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
}

.dock__bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 18px;
  cursor: pointer;
  user-select: none;
  transition: background 0.2s var(--ease);
}

.dock__bar:hover {
  background: var(--overlay-3);
}

.dock__icon {
  font-size: 16px;
  color: var(--accent-2);
}

.dock__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.dock__state {
  font-size: 11.5px;
  color: var(--status-running);
}

.dock__arrow {
  margin-left: auto;
  color: var(--text-secondary);
  transition: transform 0.2s var(--ease);
}

.dock.is-open .dock__arrow {
  transform: rotate(180deg);
}

.dock__body {
  max-height: 44vh;
  overflow: auto;
  padding: 0 18px 18px;
}

/* ---------- 旧表单版入口 ---------- */
.form-mode__back {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 30;
  box-shadow: var(--shadow-pop);
}
</style>

<template>
  <div>
    <AppHeader />

    <div class="fm-page">
      <div class="page-head">
        <div>
          <h2 class="page-head__title">我的工作流</h2>
          <p class="page-head__sub">
            共 {{ total }} 个工作流 · 编辑与执行能力将在后续迭代开放
          </p>
        </div>
        <el-button type="primary" :icon="Plus" size="large" @click="openCreate">
          新建工作流
        </el-button>
      </div>

      <div v-loading="loading" class="list-wrap">
        <div v-if="!loading && list.length === 0" class="empty">
          <el-empty description="还没有工作流，点击右上角「新建工作流」开始吧" />
        </div>

        <div v-else class="grid">
          <WorkflowCard
            v-for="wf in list"
            :key="wf.id"
            :workflow="wf"
            @edit="onEdit"
            @delete="onDelete"
          />
        </div>

        <div v-if="total > 0" class="pager">
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="size"
            :page-sizes="[10, 20, 50]"
            :total="total"
            layout="total, sizes, prev, pager, next, jumper"
            background
            @size-change="onSizeChange"
            @current-change="load"
          />
        </div>
      </div>
    </div>

    <!-- 新建工作流弹窗 -->
    <el-dialog v-model="dialogVisible" title="新建工作流" width="480px" :close-on-click-modal="false">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="72px" @submit.prevent>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="例如：天气助手" maxlength="50" show-word-limit />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="简单描述这个工作流做什么"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取 消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">确 定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppHeader from '@/components/AppHeader.vue'
import WorkflowCard from '@/components/WorkflowCard.vue'
import { getWorkflows, createWorkflow, deleteWorkflow } from '@/api/workflow'

const router = useRouter()

const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)

const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref()
const form = reactive({ name: '', description: '' })

const rules = {
  name: [
    { required: true, message: '请输入工作流名称', trigger: 'blur' },
    { max: 50, message: '名称最多 50 个字符', trigger: 'blur' }
  ]
}

async function load() {
  loading.value = true
  try {
    // 契约：GET /api/workflows?page=&size= -> { items, total, page, size }
    const data = await getWorkflows({ page: page.value, size: size.value })
    list.value = data.items || []
    total.value = data.total ?? 0
    page.value = data.page ?? page.value
    size.value = data.size ?? size.value
  } catch (e) {
    list.value = []
  } finally {
    loading.value = false
  }
}

function onSizeChange() {
  page.value = 1
  load()
}

function openCreate() {
  form.name = ''
  form.description = ''
  formRef.value?.clearValidate()
  dialogVisible.value = true
}

async function submitCreate() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    // 契约：POST /api/workflows { name, description }
    await createWorkflow({
      name: form.name.trim(),
      description: form.description.trim()
    })
    ElMessage.success('工作流创建成功')
    dialogVisible.value = false
    page.value = 1
    await load()
  } catch (e) {
    // 错误提示已在拦截器里统一弹出
  } finally {
    submitting.value = false
  }
}

function onEdit(wf) {
  // 本周占位页，第 3 周替换为真正的编辑器
  router.push(`/workflows/${wf.id}/edit`)
}

async function onDelete(wf) {
  try {
    await ElMessageBox.confirm(
      `确定要删除「${wf.name}」吗？删除后不可恢复。`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch {
    return
  }
  try {
    // 契约：DELETE /api/workflows/{id} -> 204
    await deleteWorkflow(wf.id)
    ElMessage.success('已删除')
    if (list.value.length === 1 && page.value > 1) page.value -= 1
    await load()
  } catch (e) {
    // 错误提示已在拦截器里统一弹出
  }
}

onMounted(load)
</script>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 24px;
}

.page-head__title {
  margin: 0 0 6px;
  font-size: 26px;
  font-weight: 700;
  color: var(--fm-text-strong);
}

.page-head__sub {
  margin: 0;
  font-size: 13px;
  color: var(--fm-muted);
}

.list-wrap {
  min-height: 320px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 18px;
}

.empty {
  padding: 60px 0;
}

.pager {
  margin-top: 24px;
  display: flex;
  justify-content: flex-end;
}
</style>

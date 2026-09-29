<template>
  <div>
    <AppHeader />

    <div class="fm-page">
      <!-- ============ 一级：知识库列表 ============ -->
      <template v-if="!current">
        <header class="page-head">
          <div>
            <h1 class="page-head__title">知识库</h1>
            <p class="page-head__desc">
              上传文档建立知识库，让 LLM 节点基于你的资料回答并给出引用来源
            </p>
          </div>
          <el-button class="fm-btn-primary" :icon="Plus" @click="openCreate">新建知识库</el-button>
        </header>

        <div v-loading="loading" class="list-wrap">
          <div v-if="!loading && list.length === 0" class="empty fm-glass">
            <div class="empty__icon">
              <el-icon><Collection /></el-icon>
            </div>
            <h3 class="empty__title">还没有知识库</h3>
            <p class="empty__desc">
              上传一篇文档，让 AI 基于你的资料回答问题。支持 txt / md / pdf，单个文件不超过 5MB。
            </p>
            <el-button type="primary" :icon="Plus" @click="openCreate">创建第一个知识库</el-button>
          </div>

          <div v-else class="grid">
            <article
              v-for="kb in list"
              :key="kb.id"
              class="kb-card fm-glass"
              @click="openKb(kb)"
            >
              <div class="kb-card__body">
                <h3 class="kb-card__name">{{ kb.name }}</h3>
                <p class="kb-card__desc">{{ kb.description || '暂无描述' }}</p>
              </div>
              <footer class="kb-card__foot">
                <span class="kb-card__badge">
                  <el-icon><Document /></el-icon>
                  {{ kb.documentCount ?? 0 }} 篇文档
                </span>
                <span class="kb-card__time fm-mono">{{ formatDateTime(kb.createdAt) }}</span>
                <el-button
                  text
                  :icon="Delete"
                  class="kb-card__del"
                  @click.stop="onDeleteKb(kb)"
                />
              </footer>
            </article>
          </div>
        </div>
      </template>

      <!-- ============ 二级：文档列表 ============ -->
      <template v-else>
        <header class="page-head">
          <div class="page-head__left">
            <el-button text :icon="ArrowLeft" @click="backToList">返回</el-button>
            <div>
              <h1 class="page-head__title">{{ current.name }}</h1>
              <p class="page-head__desc">
                {{ current.description || '暂无描述' }} ·
                <span class="fm-mono">{{ docs.length }}</span> 篇文档
              </p>
            </div>
          </div>
          <div class="page-head__right">
            <el-button :icon="UploadFilled" :loading="uploading" @click="pickFile">上传文档</el-button>
            <el-button :icon="Delete" @click="onDeleteKb(current)">删除知识库</el-button>
          </div>
        </header>

        <input
          ref="fileRef"
          type="file"
          accept=".txt,.md,.pdf"
          class="file-input"
          @change="onFilePicked"
        />

        <div v-loading="docLoading" class="table-wrap fm-glass">
          <el-table :data="docs" style="width: 100%" empty-text="还没有文档">
            <el-table-column label="文件名" min-width="220">
              <template #default="{ row }">
                <span class="doc-name">
                  <el-icon><Document /></el-icon>
                  {{ row.filename }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="切片数" width="110" align="right">
              <template #default="{ row }">
                <span class="fm-mono">{{ row.chunkCount ?? 0 }}</span>
              </template>
            </el-table-column>
            <el-table-column label="大小" width="110" align="right">
              <template #default="{ row }">
                <span class="fm-mono">{{ formatFileSize(row.fileSize) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="上传时间" width="150">
              <template #default="{ row }">
                <span class="fm-mono">{{ formatDateTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" align="right">
              <template #default="{ row }">
                <el-button text type="danger" @click="onDeleteDoc(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div v-if="!docLoading && docs.length === 0" class="docs-empty">
            <el-icon class="docs-empty__icon"><UploadFilled /></el-icon>
            <p class="docs-empty__text">上传第一篇文档，让 AI 基于你的资料回答问题</p>
          </div>
        </div>
      </template>
    </div>

    <!-- 新建知识库弹窗 -->
    <el-dialog v-model="dialogVisible" title="新建知识库" width="480px" :close-on-click-modal="false">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="72px" @submit.prevent>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="例如：宠物喂养指南" maxlength="50" show-word-limit />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="简单描述这个知识库的用途"
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
import { onMounted, ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Plus,
  Delete,
  ArrowLeft,
  Document,
  Collection,
  UploadFilled
} from '@element-plus/icons-vue'
import AppHeader from '@/components/AppHeader.vue'
import {
  getKnowledgeBases,
  createKnowledgeBase,
  deleteKnowledgeBase,
  getDocuments,
  uploadDocument,
  deleteDocument,
  MAX_DOCUMENT_SIZE,
  ACCEPTED_EXTENSIONS
} from '@/api/knowledge'
import { formatDateTime, formatFileSize } from '@/utils/format'

const list = ref([])
const loading = ref(false)
const current = ref(null)
const docs = ref([])
const docLoading = ref(false)
const uploading = ref(false)
const fileRef = ref(null)

const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref()
const form = reactive({ name: '', description: '' })

const rules = {
  name: [
    { required: true, message: '请输入知识库名称', trigger: 'blur' },
    { max: 50, message: '名称最多 50 个字符', trigger: 'blur' }
  ]
}

async function load() {
  loading.value = true
  try {
    const data = await getKnowledgeBases({ page: 1, size: 50 })
    list.value = data.items || []
  } catch (e) {
    list.value = []
  } finally {
    loading.value = false
  }
}

async function loadDocs(kbId) {
  docLoading.value = true
  try {
    const data = await getDocuments(kbId)
    docs.value = data.items || []
  } catch (e) {
    docs.value = []
  } finally {
    docLoading.value = false
  }
}

function openKb(kb) {
  current.value = kb
  loadDocs(kb.id)
}

function backToList() {
  current.value = null
  docs.value = []
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
    await createKnowledgeBase({ name: form.name.trim(), description: form.description.trim() })
    ElMessage.success('知识库创建成功')
    dialogVisible.value = false
    await load()
  } catch (e) {
    // 错误提示已在拦截器统一弹出
  } finally {
    submitting.value = false
  }
}

async function onDeleteKb(kb) {
  try {
    await ElMessageBox.confirm(
      `确定删除知识库「${kb.name}」吗？该库下的 ${kb.documentCount ?? 0} 篇文档与向量数据会一并删除，不可恢复。`,
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
    await deleteKnowledgeBase(kb.id)
    ElMessage.success('已删除')
    if (current.value && current.value.id === kb.id) backToList()
    else await load()
  } catch (e) {
    // 错误提示已在拦截器统一弹出
  }
}

function pickFile() {
  fileRef.value?.click()
}

async function onFilePicked(event) {
  const file = event.target.files?.[0]
  // 置空，保证同一个文件可以连续选两次
  event.target.value = ''
  if (!file || !current.value) return

  const ext = file.name.split('.').pop()?.toLowerCase()
  if (!ACCEPTED_EXTENSIONS.includes(ext)) {
    ElMessage.warning('仅支持 txt / md / pdf 文档')
    return
  }
  if (file.size > MAX_DOCUMENT_SIZE) {
    ElMessage.warning(`文档不能超过 5MB（当前 ${formatFileSize(file.size)}）`)
    return
  }

  uploading.value = true
  try {
    const doc = await uploadDocument(current.value.id, file)
    ElMessage.success(`已上传「${doc.filename || file.name}」，切分为 ${doc.chunkCount ?? 0} 个片段`)
    await loadDocs(current.value.id)
  } catch (e) {
    // 错误提示已在拦截器统一弹出（含 DOCUMENT_TOO_LARGE / EMBEDDING_ERROR）
  } finally {
    uploading.value = false
  }
}

async function onDeleteDoc(doc) {
  if (!current.value) return
  try {
    await ElMessageBox.confirm(`确定删除文档「${doc.filename}」吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
  } catch {
    return
  }
  try {
    await deleteDocument(current.value.id, doc.id)
    ElMessage.success('已删除')
    await loadDocs(current.value.id)
  } catch (e) {
    // 错误提示已在拦截器统一弹出
  }
}

onMounted(load)
</script>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  gap: 20px;
  margin-bottom: 24px;
}

.page-head__left {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  min-width: 0;
}

.page-head__right {
  margin-left: auto;
  display: flex;
  gap: 8px;
}

.page-head__title {
  margin: 0 0 6px;
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
}

.page-head__desc {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
}

.list-wrap {
  min-height: 320px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 18px;
}

/* ---------- 知识库卡片 ---------- */
.kb-card {
  display: flex;
  flex-direction: column;
  padding: 18px 20px 14px;
  background: var(--surface-glass-strong);
  cursor: pointer;
  transition: border-color 0.2s var(--ease), transform 0.2s var(--ease), box-shadow 0.2s var(--ease);
}

.kb-card:hover {
  border-color: var(--border-glass-strong);
  transform: translateY(-2px);
  box-shadow: var(--shadow-card);
}

.kb-card__body {
  flex: 1;
}

.kb-card__name {
  margin: 0 0 8px;
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kb-card__desc {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.kb-card__foot {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--border-glass);
}

.kb-card__badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 11.5px;
  color: var(--accent-chip-text);
  background: var(--accent-soft);
  border: 1px solid var(--accent-border);
}

.kb-card__time {
  font-size: 11.5px;
  color: var(--text-faint);
}

.kb-card__del {
  margin-left: auto;
  color: var(--text-faint);
}

.kb-card__del:hover {
  color: var(--danger);
}

/* ---------- 文档表 ---------- */
.table-wrap {
  padding: 8px 8px 4px;
  background: var(--surface-glass-strong);
  overflow: hidden;
}

.file-input {
  display: none;
}

.doc-name {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--text-primary);
}

.docs-empty {
  padding: 34px 0 42px;
  text-align: center;
}

.docs-empty__icon {
  font-size: 30px;
  color: var(--accent-chip-text);
  opacity: 0.85;
}

.docs-empty__text {
  margin: 10px 0 0;
  font-size: 13px;
  color: var(--text-secondary);
}

/* ---------- 空状态 ---------- */
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 72px 24px 80px;
}

.empty__icon {
  width: 76px;
  height: 76px;
  border-radius: 22px;
  display: grid;
  place-items: center;
  font-size: 34px;
  color: var(--on-brand);
  background: var(--gradient-brand);
  box-shadow: var(--shadow-glow), var(--shadow-pop);
  margin-bottom: 22px;
}

.empty__title {
  margin: 0 0 8px;
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
}

.empty__desc {
  margin: 0 0 24px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-secondary);
  max-width: 400px;
}

:deep(.el-table) {
  --el-table-border-color: var(--border-glass);
  --el-table-header-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: var(--overlay-3);
  background: transparent;
}

:deep(.el-table th.el-table__cell) {
  background: transparent;
  color: var(--text-secondary);
  font-weight: 600;
}

@media (max-width: 900px) {
  .page-head {
    flex-direction: column;
  }
  .page-head__right {
    margin-left: 0;
  }
}
</style>

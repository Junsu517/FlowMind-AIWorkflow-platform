<template>
  <div>
    <AppHeader />
    <div class="fm-page">
      <div class="placeholder fm-glass">
        <div class="placeholder__icon">
          <el-icon><Tools /></el-icon>
        </div>
        <h2 class="placeholder__title">编辑器开发中</h2>
        <p class="placeholder__desc">
          工作流「{{ name }}」（ID: {{ id }}）的画布编辑器将在第 3 周交付。
        </p>
        <p class="placeholder__hint">届时可在此编排 input / llm / condition / http 节点，并一键执行。</p>
        <el-button type="primary" plain :icon="ArrowLeft" @click="router.push('/workflows')">
          返回列表
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Tools, ArrowLeft } from '@element-plus/icons-vue'
import AppHeader from '@/components/AppHeader.vue'
import { getWorkflow } from '@/api/workflow'

const route = useRoute()
const router = useRouter()

const id = route.params.id
const name = ref('')

onMounted(async () => {
  try {
    // 契约：GET /api/workflows/{id}
    const wf = await getWorkflow(id)
    name.value = wf.name
  } catch (e) {
    name.value = `工作流 ${id}`
  }
})
</script>

<style scoped>
.placeholder {
  padding: 72px 32px;
  text-align: center;
  background: var(--fm-glass-strong);
}

.placeholder__icon {
  width: 68px;
  height: 68px;
  margin: 0 auto 20px;
  border-radius: 20px;
  display: grid;
  place-items: center;
  font-size: 32px;
  color: #fff;
  background: linear-gradient(135deg, var(--fm-primary), var(--fm-primary-2));
  box-shadow: 0 10px 26px rgba(79, 110, 247, 0.3);
}

.placeholder__title {
  margin: 0 0 12px;
  font-size: 22px;
  font-weight: 700;
  color: var(--fm-text-strong);
}

.placeholder__desc {
  margin: 0 0 6px;
  font-size: 14px;
  color: var(--fm-text);
}

.placeholder__hint {
  margin: 0 0 26px;
  font-size: 13px;
  color: var(--fm-muted);
}
</style>

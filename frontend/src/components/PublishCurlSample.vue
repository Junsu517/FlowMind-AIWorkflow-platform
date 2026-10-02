<template>
  <div class="sample">
    <div class="sample__head">
      <span>调用示例</span>
      <el-button text :icon="DocumentCopy" @click="onCopy">
        {{ copied ? '已复制' : '复制' }}
      </el-button>
    </div>
    <pre class="fm-code sample__code">{{ code }}</pre>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { DocumentCopy } from '@element-plus/icons-vue'

const props = defineProps({
  runUrl: { type: String, default: '' }
})

const copied = ref(false)

const code = computed(() => {
  const url = props.runUrl || '<runUrl>'
  return `# 1) 触发执行（返回 202 + executionId）
curl -X POST '${url}' \\
  -H 'Content-Type: application/json' \\
  -d '{"input":{"city":"南昌"}}'

# 2) 用 executionId 轮询结果
curl '${url}/executions/<executionId>'`
})

async function onCopy() {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(code.value)
    } else {
      const ta = document.createElement('textarea')
      ta.value = code.value
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    copied.value = true
    ElMessage.success('已复制')
    setTimeout(() => (copied.value = false), 2000)
  } catch (e) {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}
</script>

<style scoped>
.sample {
  margin-top: 20px;
}

.sample__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-primary);
}

.sample__code {
  margin: 0;
  max-height: 200px;
  overflow: auto;
  white-space: pre;
}
</style>

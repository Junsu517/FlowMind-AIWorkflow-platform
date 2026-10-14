<template>
  <div class="sample">
    <div class="sample__head">
      <el-radio-group v-model="lang" size="small">
        <el-radio-button value="curl">curl</el-radio-button>
        <el-radio-button value="python">Python</el-radio-button>
        <el-radio-button value="javascript">JavaScript</el-radio-button>
      </el-radio-group>
      <el-button text :icon="DocumentCopy" @click="onCopy">
        {{ copied ? '已复制' : '复制代码' }}
      </el-button>
    </div>

    <!-- 请求体里填什么：字段名直接取自输入节点 -->
    <p class="sample__body-hint">
      请求体固定是 <code>{"input": {…}}</code>，里面的字段名 = 工作流「输入」节点的参数名：
      <code v-for="f in fields" :key="f.key" class="sample__field fm-mono">{{ f.key }}</code>
      <span v-if="!fields.length">（当前没有输入节点，用 <code>{"input":{}}</code> 即可）</span>
    </p>

    <pre class="fm-code sample__code">{{ code }}</pre>

    <p v-if="!hasRealKey" class="sample__warn">
      示例里的 Key 已失效（服务端不再返回完整 Key）。请点「重新发布」，拿到新 Key 后这里的示例会自动更新。
    </p>

    <p class="sample__note">
      调用分两步：先 POST 触发（立刻返回一个执行编号），再用编号查询结果。
      频率限制 10 次/分钟。
    </p>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { DocumentCopy } from '@element-plus/icons-vue'
import { apiKeyFromRunUrl } from '@/api/publish'

const props = defineProps({
  /** 完整调用地址（含真实 key） */
  runUrl: { type: String, default: '' },
  /** 输入节点字段：[{ key, label, type, default }] */
  fields: { type: Array, default: () => [] }
})

const lang = ref('curl')
const copied = ref(false)

/** 示例入参：有默认值就用默认值，小白照抄就能跑通 */
const sampleInput = computed(() => {
  const input = {}
  for (const f of props.fields || []) {
    if (!f?.key) continue
    const hasDefault = f.default !== undefined && f.default !== null && f.default !== ''
    input[f.key] = hasDefault ? f.default : f.type === 'number' ? 0 : ''
  }
  return input
})

const bodyJson = computed(() => JSON.stringify({ input: sampleInput.value }))

/**
 * 任务卡死命令：示例里绝不能出现 {apiKey} 这种占位符——用户会原样复制进 URL 然后 401。
 * 拿不到真实 key 就直说，不要给用户一个跑不通的模板。
 */
const hasRealKey = computed(() => {
  const key = apiKeyFromRunUrl(props.runUrl)
  return !!key && !/[{}<>\s]/.test(key)
})

const url = computed(() => props.runUrl || '<调用地址>')

const code = computed(() => {
  const u = url.value
  const body = bodyJson.value
  if (lang.value === 'python') {
    return `import time
import requests

API_URL = "${u}"

# 1) 触发执行：立刻返回，不等待结果
r = requests.post(API_URL, json=${body}, timeout=30)
r.raise_for_status()
execution_id = r.json()["executionId"]

# 2) 用执行编号轮询结果（每 2 秒一次）
while True:
    res = requests.get(f"{API_URL}/executions/{execution_id}", timeout=30).json()
    if res["status"] in ("success", "failed"):
        break
    time.sleep(2)

print(res["status"])
print(res.get("output"))`
  }
  if (lang.value === 'javascript') {
    return `const API_URL = '${u}'

// 1) 触发执行：立刻返回，不等待结果
const r = await fetch(API_URL, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(${body})
})
const { executionId } = await r.json()

// 2) 用执行编号轮询结果（每 2 秒一次）
let result
for (;;) {
  result = await (await fetch(\`\${API_URL}/executions/\${executionId}\`)).json()
  if (result.status === 'success' || result.status === 'failed') break
  await new Promise((resolve) => setTimeout(resolve, 2000))
}

console.log(result.status, result.output)`
  }
  return `# 1) 触发执行（立刻返回 202 + 执行编号）
curl -X POST '${u}' \\
  -H 'Content-Type: application/json' \\
  -d '${body}'

# 2) 用上一步返回的执行编号查结果
curl '${u}/executions/<执行编号>'`
})

async function onCopy() {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(code.value)
    } else {
      // http 环境下 clipboard 不可用，退回 execCommand
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
.sample__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.sample__body-hint {
  margin: 0 0 8px;
  font-size: 11.5px;
  line-height: 1.8;
  color: var(--text-secondary);
}

.sample__body-hint code {
  padding: 1px 6px;
  border-radius: 6px;
  background: var(--overlay-3);
  color: var(--text-primary);
  font-size: 11px;
}

.sample__field {
  margin-left: 4px;
  color: var(--accent-2-text) !important;
}

.sample__code {
  max-height: 260px;
  overflow: auto;
  white-space: pre;
}

.sample__warn {
  margin: 10px 0 0;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--warn);
  background: var(--warn-soft);
  border: 1px solid var(--warn-border);
  border-radius: var(--radius-md);
  padding: 8px 10px;
}

.sample__note {
  margin: 10px 0 0;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--text-faint);
}
</style>

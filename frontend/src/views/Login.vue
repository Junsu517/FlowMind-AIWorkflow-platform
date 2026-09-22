<template>
  <AuthShell title="欢迎回来" subtitle="登录后即可编排并执行你的工作流">
    <el-form ref="formRef" :model="form" :rules="rules" size="large" @submit.prevent>
      <el-form-item prop="username">
        <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" clearable />
      </el-form-item>
      <el-form-item prop="password">
        <el-input
          v-model="form.password"
          type="password"
          placeholder="密码"
          :prefix-icon="Lock"
          show-password
          @keyup.enter="onSubmit"
        />
      </el-form-item>

      <el-button type="primary" class="submit-btn" :loading="loading" @click="onSubmit">
        登 录
      </el-button>
    </el-form>

    <template #footer>
      还没有账号？
      <router-link to="/register" class="link">立即注册</router-link>
      <div v-if="USE_MOCK" class="mock-hint">mock 演示账号：tom / 12345678</div>
    </template>
  </AuthShell>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { User, Lock } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import AuthShell from '@/components/AuthShell.vue'
import { useUserStore } from '@/stores/user'
import { USE_MOCK } from '@/api'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度需为 3-20 位', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' }
  ]
}

async function onSubmit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    await userStore.login({ username: form.username.trim(), password: form.password })
    ElMessage.success(`欢迎回来，${userStore.displayName}`)
    const redirect = route.query.redirect
    router.replace(typeof redirect === 'string' && redirect ? redirect : '/workflows')
  } catch (e) {
    // 错误提示已在 axios 响应拦截器里统一弹出
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.submit-btn {
  width: 100%;
  height: 44px;
  margin-top: 4px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 4px;
  border: none;
  border-radius: var(--radius-md);
  background: var(--gradient-brand);
  color: var(--on-brand);
  transition: box-shadow 0.2s var(--ease), transform 0.2s var(--ease), opacity 0.2s var(--ease);
}

.submit-btn:hover {
  box-shadow: var(--shadow-glow);
  transform: translateY(-1px);
  opacity: 0.96;
}

.link {
  color: var(--accent-2);
  text-decoration: none;
  font-weight: 600;
}

.link:hover {
  text-shadow: 0 0 12px var(--accent-2-glow);
}

.mock-hint {
  margin-top: 14px;
  font-size: 12px;
  color: var(--warn);
  background: var(--warn-soft);
  border: 1px solid var(--warn-border);
  border-radius: var(--radius-md);
  padding: 8px 10px;
}

:deep(.el-form-item) {
  margin-bottom: 20px;
}

:deep(.el-input__inner) {
  color: var(--text-primary);
}

:deep(.el-input__prefix-inner .el-icon) {
  color: var(--text-secondary);
}

:deep(.el-input__wrapper) {
  background-color: var(--overlay-1);
}
</style>

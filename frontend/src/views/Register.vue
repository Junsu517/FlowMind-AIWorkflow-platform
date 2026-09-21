<template>
  <AuthShell title="创建账号" subtitle="注册后即可开始编排你的第一个工作流">
    <el-form ref="formRef" :model="form" :rules="rules" size="large" @submit.prevent>
      <el-form-item prop="username">
        <el-input v-model="form.username" placeholder="用户名（3-20 位）" :prefix-icon="User" clearable />
      </el-form-item>
      <el-form-item prop="email">
        <el-input v-model="form.email" placeholder="邮箱" :prefix-icon="Message" clearable />
      </el-form-item>
      <el-form-item prop="password">
        <el-input
          v-model="form.password"
          type="password"
          placeholder="密码（至少 6 位）"
          :prefix-icon="Lock"
          show-password
        />
      </el-form-item>
      <el-form-item prop="confirmPassword">
        <el-input
          v-model="form.confirmPassword"
          type="password"
          placeholder="确认密码"
          :prefix-icon="Lock"
          show-password
          @keyup.enter="onSubmit"
        />
      </el-form-item>

      <el-button type="primary" class="submit-btn" :loading="loading" @click="onSubmit">
        注 册
      </el-button>
    </el-form>

    <template #footer>
      已有账号？
      <router-link to="/login" class="link">去登录</router-link>
    </template>
  </AuthShell>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { User, Lock, Message } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import AuthShell from '@/components/AuthShell.vue'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const formRef = ref()
const loading = ref(false)
const form = reactive({
  username: '',
  email: '',
  password: '',
  confirmPassword: ''
})

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度需为 3-20 位', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_]+$/,
      message: '用户名只能包含字母、数字和下划线',
      trigger: 'blur'
    }
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== form.password) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: 'blur'
    }
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
    // 契约：POST /api/auth/register，请求体 { username, password, email }
    await userStore.register({
      username: form.username.trim(),
      password: form.password,
      email: form.email.trim()
    })
    ElMessage.success('注册成功，已自动登录')
    router.replace('/workflows')
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
  font-size: 15px;
  letter-spacing: 4px;
  background: linear-gradient(135deg, var(--fm-primary), var(--fm-primary-2));
  border: none;
}

.submit-btn:hover {
  opacity: 0.92;
}

.link {
  color: var(--fm-primary);
  text-decoration: none;
  font-weight: 600;
}

:deep(.el-form-item) {
  margin-bottom: 20px;
}
</style>

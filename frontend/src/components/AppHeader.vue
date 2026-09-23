<template>
  <header class="header fm-glass">
    <div class="header__left" @click="goHome">
      <span class="header__mark">FM</span>
      <span class="header__name">FlowMind</span>
    </div>

    <nav class="header__nav">
      <router-link to="/workflows" class="header__link">我的工作流</router-link>
      <router-link to="/executions" class="header__link">执行历史</router-link>
    </nav>

    <div class="header__right">
      <span v-if="USE_MOCK" class="fm-mock-tip">MOCK 模式</span>
      <el-dropdown trigger="click" @command="onCommand">
        <span class="header__user">
          <span class="header__avatar">{{ avatarText }}</span>
          <span class="header__username">{{ userStore.displayName }}</span>
          <el-icon><ArrowDown /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item disabled>{{ userStore.userInfo?.email || '未设置邮箱' }}</el-dropdown-item>
            <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { USE_MOCK } from '@/api'

const router = useRouter()
const userStore = useUserStore()

const avatarText = computed(() => (userStore.displayName || 'U').slice(0, 1).toUpperCase())

function goHome() {
  router.push('/workflows')
}

function onCommand(cmd) {
  if (cmd === 'logout') {
    userStore.logout()
    ElMessage.success('已退出登录')
    router.replace('/login')
  }
}
</script>

<style scoped>
.header {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 28px;
  padding: 12px 28px;
  border-radius: 0;
  border-left: none;
  border-right: none;
  border-top: none;
  background: var(--surface-header);
}

.header__left {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  user-select: none;
}

.header__mark {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  color: var(--on-brand);
  font-weight: 700;
  font-size: 13px;
  background: var(--gradient-brand);
  box-shadow: 0 6px 18px var(--accent-glow-strong);
}

.header__name {
  font-weight: 700;
  font-size: 17px;
  color: var(--text-primary);
  letter-spacing: 0.4px;
}

.header__nav {
  display: flex;
  gap: 6px;
  flex: 1;
}

.header__link {
  padding: 6px 12px;
  border-radius: var(--radius-md);
  font-size: 14px;
  color: var(--text-secondary);
  text-decoration: none;
  transition: all 0.2s var(--ease);
}

.header__link:hover {
  color: var(--text-primary);
  background: var(--accent-glow-soft);
}

.header__link.router-link-active {
  color: var(--text-primary);
  background: var(--accent-nav-bg-active);
  font-weight: 600;
}

.header__right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.header__user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
  font-size: 14px;
  color: var(--text-primary);
}

.header__avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: var(--on-brand);
  font-size: 13px;
  font-weight: 600;
  background: var(--gradient-brand);
}
</style>

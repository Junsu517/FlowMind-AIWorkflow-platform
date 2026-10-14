<template>
  <header class="header fm-glass">
    <div class="header__left" @click="goHome">
      <span class="header__mark">FM</span>
      <span class="header__name">FlowMind</span>
    </div>

    <nav class="header__nav">
      <router-link to="/dashboard" class="header__link" data-tour="dashboard">仪表盘</router-link>
      <router-link to="/workflows" class="header__link">我的工作流</router-link>
      <router-link to="/knowledge" class="header__link">知识库</router-link>
      <router-link to="/executions" class="header__link">执行历史</router-link>
    </nav>

    <div class="header__right">
      <span v-if="USE_MOCK" class="fm-mock-tip">MOCK 模式</span>
      <!-- 新手引导：随时可以重开（重开不写"已完成"标记，只是引导一遍） -->
      <el-tooltip content="重新看一遍新手引导" placement="bottom">
        <el-button text :icon="Compass" class="header__tour" @click="restartTour">
          新手引导
        </el-button>
      </el-tooltip>
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

    <!-- 首次进入（localStorage 没有引导标记）自动弹一次；跳过/完成都不再骚扰 -->
    <TourWelcome v-model:visible="welcomeVisible" @start="onTourStart" @skip="onTourSkip" />
  </header>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown, Compass } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { USE_MOCK } from '@/api'
import TourWelcome from '@/components/TourWelcome.vue'
import { isTourDone, markTourDone, startTour } from '@/utils/tour'

const router = useRouter()
const userStore = useUserStore()

const avatarText = computed(() => (userStore.displayName || 'U').slice(0, 1).toUpperCase())

/** 引导欢迎卡：只在"还没看过引导"时自动弹一次 */
const welcomeVisible = ref(false)

onMounted(() => {
  // 等首屏数据/布局稳一点再弹，避免和页面加载动画抢镜
  setTimeout(() => {
    if (!isTourDone()) welcomeVisible.value = true
  }, 600)
})

function goHome() {
  router.push('/workflows')
}

function onTourStart() {
  startTour(router).catch(() => {})
}

function onTourSkip() {
  markTourDone()
}

function restartTour() {
  // 重开只是引导一遍，不改标记语义（标记只表示"首次引导已处理过"）
  startTour(router).catch(() => {})
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

.header__tour {
  font-size: 13px;
  color: var(--text-secondary);
}

.header__tour:hover {
  color: var(--text-primary);
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

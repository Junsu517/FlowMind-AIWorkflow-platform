import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import App from './App.vue'
import router from './router'
import { useUserStore } from '@/stores/user'
import './styles/global.css'

async function refreshStoredUser() {
  const store = useUserStore()
  if (!store.token) return
  try {
    await store.fetchMe()
  } catch {
    // 401 时 axios 拦截器已统一清 token 并跳登录；其他错误沿用本地缓存
  }
}

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

/**
 * 可选加固：本地 user 缓存只是展示用，启动时静默以服务端为准校正一次。
 * 后端鉴权只认 JWT（改 localStorage 骗不过服务端），这里纯粹是防止前端缓存被改后长期显示错误。
 * 失败就沿用缓存，不打断启动。
 */
refreshStoredUser().catch(() => {})

app.mount('#app')

import { defineStore } from 'pinia'
import { login as apiLogin, register as apiRegister, getMe } from '@/api/auth'
import { getToken, setToken, getStoredUser, setStoredUser, clearAuth } from '@/utils/storage'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: getToken(),
    userInfo: getStoredUser()
  }),
  getters: {
    isLogin: (state) => Boolean(state.token),
    username: (state) => state.userInfo?.username || '',
    displayName: (state) => state.userInfo?.username || '未登录'
  },
  actions: {
    setAuth(token, user) {
      this.token = token
      this.userInfo = user
      setToken(token)
      setStoredUser(user)
    },
    /** 契约：POST /api/auth/login */
    async login(payload) {
      const data = await apiLogin(payload)
      this.setAuth(data.token, data.user)
      return data.user
    },
    /** 契约：POST /api/auth/register（注册成功直接返回 token，等价于注册即登录） */
    async register(payload) {
      const data = await apiRegister(payload)
      this.setAuth(data.token, data.user)
      return data.user
    },
    async fetchMe() {
      const user = await getMe()
      this.userInfo = user
      setStoredUser(user)
      return user
    },
    logout() {
      clearAuth()
      this.token = ''
      this.userInfo = null
    }
  }
})

import { defineStore } from 'pinia'
import { ref } from 'vue'

import { authApi, type UserView } from '@/api/console'

export const useUserStore = defineStore('user', () => {
  const user = ref<UserView | null>(null)
  const checked = ref(false)

  /** Restores the session after a page reload; false when not logged in. */
  async function ensureLoaded(): Promise<boolean> {
    if (checked.value) {
      return user.value !== null
    }
    try {
      user.value = await authApi.me()
    } catch {
      user.value = null
    }
    checked.value = true
    return user.value !== null
  }

  async function login(username: string, password: string) {
    user.value = await authApi.login(username, password)
    checked.value = true
  }

  async function logout() {
    try {
      await authApi.logout()
    } finally {
      user.value = null
    }
  }

  return { user, ensureLoaded, login, logout }
})

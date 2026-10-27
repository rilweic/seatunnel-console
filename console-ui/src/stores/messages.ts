import { defineStore } from 'pinia'
import { ref } from 'vue'

import { messageApi } from '@/api/console'

const POLL_MILLIS = 30_000

/** The unread count under the header bell, polled while the layout is shown. */
export const useMessageStore = defineStore('messages', () => {
  const unread = ref(0)
  let timer: number | undefined

  async function refresh() {
    try {
      unread.value = await messageApi.unreadCount()
    } catch {
      // the next poll tries again
    }
  }

  function start() {
    stop()
    refresh()
    timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') refresh()
    }, POLL_MILLIS)
  }

  function stop() {
    window.clearInterval(timer)
    timer = undefined
  }

  return { unread, refresh, start, stop }
})

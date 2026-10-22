import request from '@/utils/request'
import { getToken } from '@/utils/auth'

// 查询首页工作台聚合数据
export function getDashboardOverview() {
  return request({
    url: '/system/dashboard/overview',
    method: 'get'
  })
}

// 打开首页 SSE 数据流。使用 fetch 保留现有 Bearer Token，避免 EventSource 无法设置请求头。
export function openDashboardStream({ onOpen, onMessage, onError } = {}) {
  const controller = new AbortController()
  const baseUrl = import.meta.env.VITE_APP_BASE_API || ''
  const token = getToken()

  fetch(`${baseUrl}/system/dashboard/stream`, {
    headers: {
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    signal: controller.signal
  }).then(async response => {
    if (!response.ok || !response.body) {
      throw new Error(`SSE 请求失败: ${response.status}`)
    }
    onOpen?.()
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() || ''
      frames.forEach(frame => {
        const data = frame.split(/\r?\n/)
          .filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).trim())
          .join('\n')
        if (!data) return
        try {
          onMessage?.(JSON.parse(data))
        } catch (error) {
          onError?.(error)
        }
      })
    }
  }).catch(error => {
    if (error.name !== 'AbortError') onError?.(error)
  })

  return {
    close: () => controller.abort()
  }
}

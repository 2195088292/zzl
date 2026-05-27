import { ref } from 'vue'

const MIN_VISIBLE_TIME = 420

export const pageLoading = ref(false)

let loadingTimer = 0
let loadingStartedAt = 0

export function startPageLoading() {
  clearTimeout(loadingTimer)
  loadingStartedAt = Date.now()
  pageLoading.value = true
}

export function finishPageLoading() {
  const elapsed = Date.now() - loadingStartedAt
  const delay = Math.max(MIN_VISIBLE_TIME - elapsed, 0)
  clearTimeout(loadingTimer)
  loadingTimer = setTimeout(() => {
    pageLoading.value = false
  }, delay)
}

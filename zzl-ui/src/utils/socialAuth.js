export const socialProviders = [
  { key: 'qq', name: 'QQ', shortName: 'Q', tone: '#12b7f5' },
  { key: 'wechat', name: 'WeChat', shortName: '微', tone: '#07c160' },
  { key: 'github', name: 'GitHub', shortName: 'GH', tone: '#17151f' },
  { key: 'gitee', name: 'Gitee', shortName: '码', tone: '#c71d23' },
  { key: 'google', name: 'Google', shortName: 'G', tone: '#4285f4' },
  { key: 'facebook', name: 'Facebook', shortName: 'f', tone: '#1877f2' }
]

export function startSocialAuth(provider, mode = 'login', redirect = '/index') {
  const baseApi = import.meta.env.VITE_APP_BASE_API || ''
  const safeRedirect = redirect && redirect.startsWith('/') ? redirect : '/index'
  const target = `${baseApi}/auth/social/${provider}/authorize?mode=${mode}&redirect=${encodeURIComponent(safeRedirect)}`
  window.location.href = target
}

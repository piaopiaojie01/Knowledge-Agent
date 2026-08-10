// 主题：auto（按时间自动） / light / dark
// 自动规则：06:00–18:00 日间，其余夜间
const THEME_KEY = 'ka_theme'
const DAY_START = 6
const DAY_END = 18

export function resolveTheme(pref, date = new Date()) {
  if (pref === 'light' || pref === 'dark') return pref
  const h = date.getHours()
  return h >= DAY_START && h < DAY_END ? 'light' : 'dark'
}

export function getThemePref() {
  return localStorage.getItem(THEME_KEY) || 'auto'
}

export function applyTheme() {
  const pref = getThemePref()
  const theme = resolveTheme(pref)
  document.documentElement.dataset.theme = theme
  return { pref, theme }
}

export function setThemePref(pref) {
  localStorage.setItem(THEME_KEY, pref)
  return applyTheme()
}

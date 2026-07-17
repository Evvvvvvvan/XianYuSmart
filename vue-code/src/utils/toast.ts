let container: HTMLDivElement | null = null
const activeMessages = new Set<string>()

function getContainer() {
  if (!container) {
    container = document.createElement('div')
    container.className = 'toast-container'
    container.setAttribute('aria-live', 'polite')
    container.style.cssText = 'position:fixed;top:20px;left:50%;transform:translateX(-50%);z-index:99999;display:flex;flex-direction:column;align-items:center;gap:8px;width:min(420px,calc(100vw - 32px));pointer-events:none;'
    document.body.appendChild(container)
  }
  return container
}

const COLOR_MAP: Record<string, string> = {
  success: '#079455',
  error: '#d92d20',
  warning: '#dc6803',
  info: '#155eef',
}

const ICON_MAP: Record<string, string> = {
  success: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>',
  error: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>',
  warning: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>',
  info: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>',
}

function show(message: string, type: string = 'info', duration: number = 2500) {
  const key = `${type}:${message}`
  if (activeMessages.has(key)) return
  activeMessages.add(key)

  const color = COLOR_MAP[type] ?? COLOR_MAP.info!
  const el = document.createElement('div')
  el.setAttribute('role', type === 'error' ? 'alert' : 'status')
  el.style.cssText = `
    display:flex;align-items:flex-start;gap:9px;width:fit-content;max-width:100%;box-sizing:border-box;
    padding:10px 14px;border:1px solid #e4e7ec;border-left:3px solid ${color};border-radius:8px;
    background:#fff;color:#344054;font-size:14px;font-weight:500;line-height:1.5;
    box-shadow:0 8px 24px rgba(16,24,40,.12);pointer-events:auto;
    animation:toast-in .2s ease forwards;font-family:inherit;
  `

  const icon = document.createElement('span')
  icon.setAttribute('aria-hidden', 'true')
  icon.style.cssText = `display:flex;flex:0 0 auto;margin-top:2px;color:${color};`
  icon.innerHTML = ICON_MAP[type] ?? ICON_MAP.info!

  const text = document.createElement('span')
  text.style.cssText = 'min-width:0;overflow-wrap:anywhere;white-space:pre-line;'
  text.textContent = message
  el.append(icon, text)
  getContainer().appendChild(el)

  const remove = () => {
    activeMessages.delete(key)
    el.style.animation = 'toast-out .15s ease forwards'
    setTimeout(() => el.remove(), 150)
  }
  setTimeout(remove, duration)
}

export const toast = {
  success: (msg: string) => show(msg, 'success'),
  error: (msg: string) => show(msg, 'error'),
  warning: (msg: string) => show(msg, 'warning'),
  info: (msg: string) => show(msg, 'info'),
}

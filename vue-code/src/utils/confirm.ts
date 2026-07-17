export function showConfirm(message: string, title: string = '确认'): Promise<void> {
  return new Promise((resolve, reject) => {
    const overlay = document.createElement('div')
    overlay.setAttribute('role', 'presentation')
    overlay.style.cssText = `
      position:fixed;inset:0;z-index:99998;padding:20px;
      background:rgba(16,24,40,.42);display:flex;align-items:center;justify-content:center;
      animation:confirm-in .18s ease forwards;
    `

    const dialog = document.createElement('div')
    dialog.setAttribute('role', 'dialog')
    dialog.setAttribute('aria-modal', 'true')
    dialog.setAttribute('aria-labelledby', 'confirm-title')
    dialog.setAttribute('aria-describedby', 'confirm-message')
    dialog.style.cssText = `
      width:380px;max-width:100%;overflow:hidden;background:#fff;border:1px solid #e4e7ec;
      border-radius:10px;box-shadow:0 20px 48px rgba(16,24,40,.18);font-family:inherit;
    `

    const content = document.createElement('div')
    content.style.cssText = 'padding:22px 22px 20px;'
    const titleElement = document.createElement('div')
    titleElement.id = 'confirm-title'
    titleElement.style.cssText = 'font-size:17px;font-weight:600;color:#101828;margin-bottom:8px;'
    titleElement.textContent = title
    const messageElement = document.createElement('div')
    messageElement.id = 'confirm-message'
    messageElement.style.cssText = 'font-size:14px;color:#667085;line-height:1.6;white-space:pre-line;overflow-wrap:anywhere;'
    messageElement.textContent = message
    content.append(titleElement, messageElement)

    const actions = document.createElement('div')
    actions.style.cssText = 'display:flex;justify-content:flex-end;gap:10px;padding:14px 22px;border-top:1px solid #eaecf0;background:#f9fafb;'
    const cancelButton = document.createElement('button')
    cancelButton.type = 'button'
    cancelButton.textContent = '取消'
    cancelButton.style.cssText = 'height:36px;padding:0 14px;border:1px solid #d0d5dd;border-radius:6px;background:#fff;color:#344054;cursor:pointer;font-weight:500;'
    const confirmButton = document.createElement('button')
    confirmButton.type = 'button'
    confirmButton.textContent = '确定'
    confirmButton.style.cssText = 'height:36px;padding:0 14px;border:1px solid #155eef;border-radius:6px;background:#155eef;color:#fff;cursor:pointer;font-weight:600;'
    actions.append(cancelButton, confirmButton)
    dialog.append(content, actions)
    overlay.appendChild(dialog)
    document.body.appendChild(overlay)

    let settled = false
    const cleanup = () => {
      document.removeEventListener('keydown', handleKeydown)
      overlay.style.animation = 'confirm-out .15s ease forwards'
      setTimeout(() => overlay.remove(), 150)
    }
    const cancel = () => {
      if (settled) return
      settled = true
      cleanup()
      reject('cancel')
    }
    const confirm = () => {
      if (settled) return
      settled = true
      cleanup()
      resolve()
    }
    const handleKeydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') cancel()
      if (event.key === 'Enter') confirm()
    }

    cancelButton.addEventListener('click', cancel)
    confirmButton.addEventListener('click', confirm)
    overlay.addEventListener('click', (event) => {
      if (event.target === overlay) cancel()
    })
    document.addEventListener('keydown', handleKeydown)
    requestAnimationFrame(() => confirmButton.focus())
  })
}

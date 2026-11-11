import type { Directive, DirectiveBinding } from 'vue'

const OVERLAY_ATTR = 'data-loading-overlay'

const spinnerMarkup = `<svg class="vl-spin" width="22" height="22" viewBox="0 0 24 24" fill="none">
  <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-opacity="0.25" stroke-width="3" />
  <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" stroke-width="3" stroke-linecap="round" />
</svg>`

function toggle(el: HTMLElement, show: boolean) {
  const existing = el.querySelector<HTMLElement>(`:scope > [${OVERLAY_ATTR}]`)
  if (show) {
    if (existing) return
    if (getComputedStyle(el).position === 'static') {
      el.style.position = 'relative'
    }
    const overlay = document.createElement('div')
    overlay.setAttribute(OVERLAY_ATTR, '')
    overlay.className = 'vl-overlay'
    overlay.innerHTML = spinnerMarkup
    el.appendChild(overlay)
  } else {
    existing?.remove()
  }
}

/** v-loading="boolean" — overlays a spinner on the host element. */
export const vLoading: Directive<HTMLElement, boolean> = {
  mounted(el, binding: DirectiveBinding<boolean>) {
    toggle(el, !!binding.value)
  },
  updated(el, binding: DirectiveBinding<boolean>) {
    toggle(el, !!binding.value)
  },
  unmounted(el) {
    toggle(el, false)
  },
}

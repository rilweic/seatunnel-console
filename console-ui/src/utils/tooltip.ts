/**
 * One look and behaviour for every text tooltip (table overflow hints, validation details, button
 * hints): light, no arrow, and close enough to its trigger that the pointer can move onto it.
 */
export const textTooltip = {
  effect: 'light',
  popperClass: 'app-text-tooltip',
  showArrow: false,
  enterable: true,
  showAfter: 200,
  hideAfter: 300,
  offset: 4,
} as const

const POPPER = '.el-popper.app-text-tooltip'

/**
 * Keeps a text tooltip open while its text is being selected and while it holds a selection, so
 * the text can be copied: a hover tooltip otherwise closes as soon as the pointer leaves it, which
 * a selection drag easily does. A held tooltip closes on the next click outside it or on Escape.
 */
export function keepTooltipsWhileCopying() {
  let selecting: HTMLElement | null = null // tooltip a selection drag started in
  let held: HTMLElement | null = null // tooltip whose text is selected

  const tooltipOf = (target: EventTarget | null) =>
    target instanceof Element ? target.closest<HTMLElement>(POPPER) : null

  // The tooltip closes itself on mouseleave; replaying one lets it do so after being held.
  const release = () => {
    const tooltip = held
    held = null
    tooltip?.dispatchEvent(new MouseEvent('mouseleave'))
  }

  document.addEventListener('mousedown', (e) => {
    const tooltip = tooltipOf(e.target)
    if (held && tooltip !== held) release()
    selecting = tooltip
    document.documentElement.classList.toggle('selecting-tooltip', !!tooltip)
  }, true)

  document.addEventListener('mouseup', () => {
    document.documentElement.classList.remove('selecting-tooltip')
    if (!selecting) return
    const selection = window.getSelection()
    if (selection && !selection.isCollapsed && selecting.contains(selection.anchorNode)) held = selecting
    selecting = null
  }, true)

  // Captured at the document, before the tooltip or its trigger sees them: while dragging, no
  // leave or enter may close this tooltip or open another (a table opens one per hovered cell).
  const swallow = (e: Event) => {
    if (selecting || (held && e.target === held)) e.stopPropagation()
  }
  document.addEventListener('mouseleave', swallow, true)
  document.addEventListener('mouseenter', (e) => {
    if (selecting) e.stopPropagation()
  }, true)

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && held) release()
  })
}

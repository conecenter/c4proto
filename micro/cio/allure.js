
const c4findParent = (node, cond) => node && (cond(node) ? node : c4findParent(node.parentNode, cond))

const c4load = url => fetch(url).then(r => r.ok ? r.json() : null).catch(() => null)

document.addEventListener("click", async ev => {
  const uid = location.hash.match(/^#([^/]+)\/history$/)?.[1]
  const item = c4findParent(ev.target, n => n.dataset?.testid === "test-result-history-item")
  if (!item || !uid) return
  const historyF = c4load(`./data/test-results/${uid}.json`)
  const linksF = c4load(`/allure/c4-history-links-${c4proj}.json`)
  // index among visible history items, instead of parsing date text
  const items = [...document.querySelectorAll('[data-testid="test-result-history-item"]')]
  const ix = items.indexOf(item)
  if (ix < 0) return
  const toId = (await historyF)?.history?.[ix]?.id
  const url = (await linksF)?.[toId]
  if (url) location.href = `${url}#${toId}/history`
})

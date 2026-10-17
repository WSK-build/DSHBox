'use strict'

/** Reads the selection baseline only from the two authoritative Browser open outcomes. */
function confirmedOpenSequence(value) {
  let data
  try { data = JSON.parse(value && value.dataJson || '{}') } catch { return null }
  if (!data || typeof data !== 'object' || Array.isArray(data) ||
      !Number.isSafeInteger(data.selectionSequence) || data.selectionSequence < 0) return null
  if (data.event === 'opened') return data.selectionSequence
  if (data.event !== 'navigation_accepted' || value.executed !== 'true' ||
      !Number.isSafeInteger(data.navigationId) || data.navigationId < 1 || data.loadState !== 'accepted') return null
  try {
    const url = new URL(data.requestedUrl)
    if (!['http:', 'https:'].includes(url.protocol) || !url.hostname) return null
  } catch { return null }
  return data.selectionSequence
}

module.exports = { confirmedOpenSequence }

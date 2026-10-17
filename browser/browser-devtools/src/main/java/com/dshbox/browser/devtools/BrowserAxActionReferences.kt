package com.dshbox.browser.devtools

import java.util.UUID

/** Retains renderer node identities privately, scoped to one authorized AX observation. */
internal class BrowserAxActionReferences(private val now: () -> Long) {
    internal data class Identity(
        val targetId: String,
        val connectionEpoch: Long,
        val documentIdentity: String,
        val authorizationEpoch: Long,
    )

    private data class Entry(val identity: Identity, val expiresAt: Long, val candidate: BrowserAxActionCandidate)
    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun replace(identity: Identity, candidates: Map<String, BrowserAxActionCandidate>): Map<String, String> {
        entries.clear()
        require(identity.targetId.isNotBlank() && identity.documentIdentity.isNotBlank())
        val startedAt = now()
        require(startedAt >= 0 && startedAt <= Long.MAX_VALUE - TTL_MILLIS)
        val nonce = UUID.randomUUID().toString()
        val refs = linkedMapOf<String, String>()
        candidates.entries.asSequence().filter { (axRef, candidate) ->
            candidate.backendNodeId > 0 && candidate.frameId.isNotBlank() && axRef.startsWith("ax:")
        }.take(256).forEachIndexed { index, (axRef, candidate) ->
            val actionRef = "axa:$nonce:$index"
            entries[actionRef] = Entry(identity, startedAt + TTL_MILLIS, candidate)
            refs[axRef] = actionRef
        }
        return refs
    }

    @Synchronized
    fun resolve(actionRef: String, identity: Identity): BrowserAxActionCandidate? {
        val entry = entries[actionRef] ?: return null
        if (entry.identity != identity || now() >= entry.expiresAt) {
            entries.remove(actionRef)
            return null
        }
        return entry.candidate
    }

    @Synchronized
    fun clear() = entries.clear()

    companion object { const val TTL_MILLIS = 30_000L }
}

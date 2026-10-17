package com.dshbox.browser.devtools

import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Fetch continues let Chromium compute body framing; explicit Content-Length is rejected by CDP. */
internal fun interceptedRequestHeaders(original: JSONObject, overrides: JSONObject, replacesBody: Boolean): JSONObject {
    val values = JSONObject(original.toString())
    val keys = overrides.keys()
    while (keys.hasNext()) {
        val name = keys.next()
        values.keys().asSequence().firstOrNull { it.equals(name, true) }?.let(values::remove)
        values.put(name, overrides.get(name))
    }
    values.keys().asSequence().toList().filter {
        it.equals("content-length", true) || (replacesBody &&
            (it.equals("content-encoding", true) || it.equals("transfer-encoding", true)))
    }.forEach(values::remove)
    return values
}

internal fun compileUrlPattern(pattern: String): Regex {
    val expression = pattern.split('*').joinToString(".*") { Regex.escape(it) }
    return Regex("^$expression$")
}

internal fun boundedCoordinate(value: Any?): Double? {
    val number = value as? Number ?: return null
    val result = number.toDouble()
    return result.takeIf { it.isFinite() && it in 0.0..10_000.0 }
}

/** Input coordinates are CSS pixels in the main-frame layout viewport, not screen pixels. */
internal data class CdpInputViewport(val left: Double, val top: Double, val width: Double, val height: Double) {
    fun contains(x: Double, y: Double): Boolean = x.isFinite() && y.isFinite() &&
        x >= left && y >= top && x < left + width && y < top + height
}

internal fun inputViewport(metrics: JSONObject): CdpInputViewport? {
    val visual = metrics.optJSONObject("cssVisualViewport") ?: return null
    val left = visual.optDouble("offsetX", Double.NaN)
    val top = visual.optDouble("offsetY", Double.NaN)
    val width = visual.optDouble("clientWidth", Double.NaN)
    val height = visual.optDouble("clientHeight", Double.NaN)
    if (listOf(left, top, width, height).any { !it.isFinite() } ||
        left < 0 || top < 0 || width <= 0 || height <= 0 ||
        left + width > 100_000 || top + height > 100_000) return null
    return CdpInputViewport(left, top, width, height)
}

internal fun boundedDurationMillis(started: Long, completed: Long?): Long? =
    completed?.minus(started)?.coerceAtLeast(0L)

internal fun boundedBodyPrefix(bytes: ByteArray, perEntryRemaining: Int, totalRemaining: Int): ByteArray =
    bytes.copyOf(minOf(bytes.size, perEntryRemaining.coerceAtLeast(0), totalRemaining.coerceAtLeast(0)))

internal fun isUnsupportedCdpMethod(code: Int, message: String): Boolean =
    code == -32601 || message.contains("method not found", ignoreCase = true)

internal fun quoteJavaScriptString(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20 || character.code in 0xD800..0xDFFF || character == '\u2028' || character == '\u2029') {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

internal fun buildDomSummaryExpression(selector: String): String {
    val quoted = quoteJavaScriptString(selector.takeIf { it.isNotBlank() } ?: "*")
    return "(()=>{const q=$quoted;const edit='[contenteditable]:not([contenteditable=false]),input,textarea,select,[role=textbox]';" +
        "const es=Array.from(document.querySelectorAll(q)).filter(e=>!e.matches(edit)&&!e.closest(edit)&&" +
        "!e.closest('[hidden],[aria-hidden=true],script,style,template')&&e.getClientRects().length>0).slice(0,50);" +
        "return JSON.stringify({title:document.title.slice(0,256),url:location.origin,items:es.map(e=>{const c=e.cloneNode(true);" +
        "c.querySelectorAll(edit+',script,style,template,[hidden],[aria-hidden=true]').forEach(n=>n.remove());return{tag:e.tagName.toLowerCase()," +
        "role:(e.getAttribute('role')||'').slice(0,48),label:(e.getAttribute('aria-label')||e.getAttribute('title')||c.innerText||'')" +
        ".replace(/\\s+/g,' ').trim().slice(0,256),rect:(()=>{const r=e.getBoundingClientRect();return{x:Math.round(r.x)," +
        "y:Math.round(r.y),width:Math.round(r.width),height:Math.round(r.height)}})()}})})})()"
}

internal fun buildTapPointExpression(selector: String): String {
    val quoted = quoteJavaScriptString(selector)
    return "(()=>{const es=Array.from(document.querySelectorAll($quoted));if(es.length!==1)return null;const e=es[0];" +
        "if(e.closest('[hidden],[aria-hidden=true],script,style,template,iframe,frame,object,embed')||!e.getClientRects().length)return null;" +
        "for(let n=e;n;n=n.parentElement){const s=getComputedStyle(n);if(s.display==='none'||s.visibility==='hidden'||s.visibility==='collapse'||s.pointerEvents==='none'||Number(s.opacity)===0)return null;}" +
        "const r=e.getBoundingClientRect();const left=Math.max(0,r.left);const top=Math.max(0,r.top);" +
        "const right=Math.min(innerWidth,r.right);const bottom=Math.min(innerHeight,r.bottom);" +
        "if(r.width<=0||r.height<=0||right<=left||bottom<=top)return null;const x=left+(right-left)/2;const y=top+(bottom-top)/2;" +
        "if(!Number.isFinite(x)||!Number.isFinite(y))return null;" +
        "const h=document.elementFromPoint(x,y);if(!(h===e||e.contains(h)))return null;return JSON.stringify({x,y})})()"
}

/** A concurrent aggregate byte cap, in addition to the event-count queue bound. */
internal class CdpEventByteBudget(private val limit: Long) {
    private val usedBytes = AtomicLong()

    fun reserve(bytes: Int): Boolean {
        if (bytes <= 0 || bytes.toLong() > limit) return false
        while (true) {
            val current = usedBytes.get()
            if (current + bytes > limit) return false
            if (usedBytes.compareAndSet(current, current + bytes)) return true
        }
    }

    fun release(bytes: Int) {
        val remaining = usedBytes.addAndGet(-bytes.toLong())
        check(remaining >= 0L) { "EVENT_BUDGET_UNDERFLOW" }
    }

    fun currentBytes(): Long = usedBytes.get()
}

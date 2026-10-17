package com.dshbox.browser.devtools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class DevToolsPoliciesTest {
    @Test
    fun inputBoundsUseCssVisualViewportAndExcludeEdgesAndOffscreenPoints() {
        val metrics = org.json.JSONObject().put("cssVisualViewport", org.json.JSONObject()
            .put("offsetX", 12.5).put("offsetY", 8.25).put("clientWidth", 320.5).put("clientHeight", 480.75)
            .put("pageX", 400).put("pageY", 900).put("scale", 2))
        val viewport = inputViewport(metrics)!!
        assertTrue(viewport.contains(12.5, 8.25))
        assertTrue(viewport.contains(332.9, 488.9))
        assertFalse(viewport.contains(333.0, 20.0))
        assertFalse(viewport.contains(20.0, 489.0))
        assertFalse(viewport.contains(0.0, 20.0))
        assertFalse(viewport.contains(20.0, Double.NaN))
        assertNull(inputViewport(org.json.JSONObject()))
        assertNull(inputViewport(org.json.JSONObject().put("cssVisualViewport", org.json.JSONObject()
            .put("offsetX", 0).put("offsetY", 0).put("clientWidth", 0).put("clientHeight", 480))))
    }

    @Test
    fun interceptedHeadersDropForbiddenLengthAndReplaceBodyEncodingWithoutMutatingInputs() {
        val original = org.json.JSONObject().put("Content-Length", "80").put("Content-Encoding", "gzip")
            .put("Transfer-Encoding", "chunked").put("X-Test", "old").put("Cookie", "session=fixture")
        val overrides = org.json.JSONObject().put("content-length", "99").put("x-test", "new")
        val result = interceptedRequestHeaders(original, overrides, true)
        assertFalse(result.keys().asSequence().any { it.equals("content-length", true) })
        assertFalse(result.has("Content-Encoding"))
        assertFalse(result.has("Transfer-Encoding"))
        assertEquals("new", result.getString("x-test"))
        assertEquals("session=fixture", result.getString("Cookie"))
        assertEquals("80", original.getString("Content-Length"))
        assertEquals("99", overrides.getString("content-length"))
        val unchangedBody = interceptedRequestHeaders(original, org.json.JSONObject(), false)
        assertFalse(unchangedBody.has("Content-Length"))
        assertEquals("gzip", unchangedBody.getString("Content-Encoding"))
    }
    @Test
    fun urlPatternTreatsOnlyAsteriskAsWildcard() {
        val regex = compileUrlPattern("https://example.test/a.b/*")
        assertTrue(regex.matches("https://example.test/a.b/item"))
        assertFalse(regex.matches("https://example.test/axb/item"))
        assertFalse(regex.matches("https://elsewhere.test/a.b/item"))
    }

    @Test
    fun coordinatesAcceptFiniteDecimalsAndRejectCoercionsOrOutOfRangeValues() {
        assertEquals(4.25, boundedCoordinate(4.25)!!, 0.0)
        assertEquals(10_000.0, boundedCoordinate(10_000)!!, 0.0)
        assertNull(boundedCoordinate("4.25"))
        assertNull(boundedCoordinate(Double.NaN))
        assertNull(boundedCoordinate(Double.POSITIVE_INFINITY))
        assertNull(boundedCoordinate(-0.1))
        assertNull(boundedCoordinate(10_000.1))
    }

    @Test
    fun durationIsNullUntilCompletedAndNeverNegative() {
        assertNull(boundedDurationMillis(100L, null))
        assertEquals(75L, boundedDurationMillis(100L, 175L) ?: -1L)
        assertEquals(0L, boundedDurationMillis(100L, 50L) ?: -1L)
    }

    @Test
    fun streamChunkPrefixPreservesRawUtf8BytesAcrossChunkBoundariesAndEnforcesCaps() {
        val source = "A😃B".toByteArray(Charsets.UTF_8)
        val chunks = listOf(source.copyOfRange(0, 3), source.copyOfRange(3, source.size))
        val accepted = chunks.flatMap { boundedBodyPrefix(it, 16, 16).toList() }.toByteArray()
        assertTrue(source.contentEquals(accepted))
        assertEquals(2, boundedBodyPrefix(source, 2, 9).size)
        assertEquals(1, boundedBodyPrefix(source, 8, 1).size)
        assertEquals(0, boundedBodyPrefix(source, 0, 10).size)
    }

    @Test
    fun onlyProtocolMethodNotFoundIsReportedAsUnsupported() {
        assertTrue(isUnsupportedCdpMethod(-32601, "Method not found"))
        assertTrue(isUnsupportedCdpMethod(-1, "method not found"))
        assertFalse(isUnsupportedCdpMethod(-32000, "No resource with given identifier found"))
    }

    @Test
    fun javascriptStringEncodingEscapesSyntaxAndLineSeparators() {
        val isolatedSurrogate = String(charArrayOf(0xD800.toChar()))
        val input = "q\"\\\u0000\b\u000C\n\r\t\u2028\u2029" + isolatedSurrogate + "\uD83D\uDE00"
        assertEquals(
            "\"q\\\"\\\\\\u0000\\b\\f\\n\\r\\t\\u2028\\u2029\\ud800\\ud83d\\ude00\"",
            quoteJavaScriptString(input),
        )
    }

    @Test
    fun generatedDomSummaryRunsWithAnEmptySelectorAndFiltersEditableContent() {
        val harness = """
            const vm = require('node:vm');
            const input = JSON.parse(require('node:fs').readFileSync(0, 'utf8'));
            const isEditable = node => node.editable === true;
            const isHidden = node => node.hidden === true;
            const makeNode = (props = {}) => ({
              tagName: props.tagName || 'DIV',
              editable: props.editable || false,
              hidden: props.hidden || false,
              text: props.text || '',
              attrs: props.attrs || {},
              get innerText() { return this.text; },
              matches(selector) {
                if (selector.includes('input,textarea,select,[role=textbox]')) return isEditable(this);
                if (selector.includes('[hidden],[aria-hidden=true]')) return isHidden(this);
                return false;
              },
              closest(selector) {
                if (selector.includes('input,textarea,select,[role=textbox]')) return isEditable(this) ? this : null;
                if (selector.includes('[hidden],[aria-hidden=true]')) return isHidden(this) ? this : null;
                return null;
              },
              getClientRects() { return isHidden(this) ? [] : [{}]; },
              getBoundingClientRect() { return { left: 0, top: 0, right: 10, bottom: 10, width: 10, height: 10 }; },
              getAttribute(name) { return this.attrs[name] || null; },
              cloneNode() {
                return { innerText: this.text, querySelectorAll() { return []; } };
              },
              querySelectorAll() { return []; }
            });
            const visible = makeNode({ text: 'Safe summary text', attrs: { title: 'Safe title' } });
            const fieldInput = makeNode({ tagName: 'INPUT', editable: true });
            Object.defineProperty(fieldInput, 'value', { get() { throw new Error('input value accessed'); } });
            const editable = makeNode({ editable: true, text: 'private editable text' });
            const hidden = makeNode({ hidden: true, text: 'hidden private text' });
            const document = {
              title: 'Fixture page',
              querySelectorAll(selector) {
                if (selector !== input.expectedSelector) throw new Error('selector literal changed during encoding');
                return [visible, fieldInput, editable, hidden];
              }
            };
            const result = vm.runInNewContext(input.expression, {
              document,
              location: { origin: 'http://fixture.test' },
              JSON
            });
            const summary = JSON.parse(result);
            if (summary.items.length !== 1 || summary.items[0].label !== 'Safe title') {
              throw new Error('unexpected summary result');
            }
            if (result.includes('private') || result.includes('INPUT')) {
              throw new Error('editable or hidden content was included');
            }
            process.stdout.write('DOM_SUMMARY_OK');
        """.trimIndent()
        val isolatedSurrogate = String(charArrayOf(0xD800.toChar()))
        val selectors = listOf("", "div[data-value=\"quote\\\\slash\u0001\u2028\"]$isolatedSurrogate")
        for (selector in selectors) {
            val expression = buildDomSummaryExpression(selector)
            val expectedSelector = selector.takeIf { it.isNotBlank() } ?: "*"
            val payload = "{\"expression\":${quoteJavaScriptString(expression)},\"expectedSelector\":${quoteJavaScriptString(expectedSelector)}}"
            val process = ProcessBuilder("node", "-e", harness).redirectErrorStream(true).start()
            process.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val completed = process.waitFor(10, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly()
            val output = ByteArrayOutputStream()
            process.inputStream.use { it.copyTo(output) }
            assertTrue("Node DOM execution timed out", completed)
            val nodeOutput = output.toString(Charsets.UTF_8.name())
            assertEquals("Node DOM execution failed; exitValue=${process.exitValue()}, output=$nodeOutput", 0, process.exitValue())
            assertEquals("DOM_SUMMARY_OK", nodeOutput)
        }
    }

    @Test
    fun generatedTapPointRequiresUniqueVisibleUnblockedTargetAndAllowsInputs() {
        val expression = buildTapPointExpression("#target")
        val harness = """
            const vm = require('node:vm');
            const input = JSON.parse(require('node:fs').readFileSync(0, 'utf8'));
            const expression = input.expression;
            const makeNode = ({editable = false, hidden = false, frame = false, opacity = 1, rect = {left: 10, top: 20, width: 40, height: 20}} = {}) => ({
              editable, hidden, frame, opacity, parentElement: null,
              matches(selector) { return selector.includes('input,textarea,select,[role=textbox]') && this.editable; },
              closest(selector) {
                if (selector.includes('input,textarea,select,[role=textbox]')) return this.editable ? this : null;
                if (selector.includes('[hidden],[aria-hidden=true]')) return this.hidden || this.frame ? this : null;
                return null;
              },
              getClientRects() { return this.hidden ? [] : [{}]; },
              getBoundingClientRect() { return { ...rect, right: rect.left + rect.width, bottom: rect.top + rect.height }; },
              contains(node) { return node === this; }
            });
            const run = (target, hit = target) => vm.runInNewContext(expression, {
              document: { querySelectorAll(selector) { if (selector !== input.selector) throw new Error('unexpected selector'); return Array.isArray(target) ? target : [target]; }, elementFromPoint() { return hit; } },
              getComputedStyle(node) { return { display: 'block', visibility: 'visible', pointerEvents: 'auto', opacity: String(node.opacity) }; },
              innerWidth: 300,
              innerHeight: 200,
              JSON,
              Number
            });
            const visible = makeNode();
            if (run(visible) !== JSON.stringify({x: 30, y: 30})) throw new Error('visible target center was not returned');
            if (run([visible, visible]) !== null) throw new Error('ambiguous target accepted');
            if (run(makeNode({hidden: true})) !== null) throw new Error('hidden target accepted');
            if (run(makeNode({frame: true})) !== null) throw new Error('frame target accepted');
            const fieldInput = makeNode({editable: true});
            Object.defineProperty(fieldInput, 'value', { get() { throw new Error('input value accessed'); } });
            if (run(fieldInput) !== JSON.stringify({x: 30, y: 30})) throw new Error('visible input could not be focused');
            if (run(makeNode({rect: {left: -50, top: 20, width: 20, height: 20}})) !== null) throw new Error('offscreen target accepted');
            if (run(makeNode({rect: {left: -10, top: 20, width: 30, height: 20}})) !== JSON.stringify({x: 10, y: 30})) throw new Error('clipped target center was not used');
            const transparentAncestor = makeNode({opacity: 0});
            const transparentChild = makeNode();
            transparentChild.parentElement = transparentAncestor;
            if (run(transparentChild) !== null) throw new Error('transparent ancestor target accepted');
            if (run(visible, makeNode()) !== null) throw new Error('occluded target accepted');
            process.stdout.write('TAP_POINT_OK');
        """.trimIndent()

        val process = ProcessBuilder("node", "-e", harness).redirectErrorStream(true).start()
        val payload = "{\"expression\":${quoteJavaScriptString(expression)},\"selector\":${quoteJavaScriptString("#target")}}"
        process.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val completed = process.waitFor(10, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        val output = ByteArrayOutputStream()
        process.inputStream.use { it.copyTo(output) }
        assertTrue("Node tap-point execution timed out", completed)
        val nodeOutput = output.toString(Charsets.UTF_8.name())
        assertEquals("Node tap-point execution failed; exitValue=${process.exitValue()}, output=$nodeOutput", 0, process.exitValue())
        assertEquals("TAP_POINT_OK", nodeOutput)
    }

    @Test
    fun aggregateEventBudgetNeverExceedsItsByteLimit() {
        val budget = CdpEventByteBudget(8)
        assertTrue(budget.reserve(5))
        assertFalse(budget.reserve(4))
        assertEquals(5L, budget.currentBytes())
        budget.release(5)
        assertEquals(0L, budget.currentBytes())
        assertFalse(budget.reserve(9))
    }
}

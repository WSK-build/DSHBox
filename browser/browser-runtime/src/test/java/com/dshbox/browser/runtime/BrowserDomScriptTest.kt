package com.dshbox.browser.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class BrowserDomScriptTest {
    @Test
    fun generatedLocateTemplateHasFixedBoundedGuardsAndDoesNotInjectCallerSource() {
        val script = BrowserDomScript.locate(
            nonce = "fresh", previousNonce = "old", strategy = "label", value = "Email", exact = true,
            name = null, scopeNonce = "scope", scopeIndex = 0,
        )
        assertTrue(script.contains("const walkAccessible"))
        assertTrue(script.contains("createTreeWalker(root"))
        assertFalse(script.contains("querySelectorAll"))
        assertTrue(script.contains("LOCATE_SCAN_LIMIT") == false)
        assertTrue(script.contains(">5000"))
        assertTrue(script.contains(">20000"))
        assertTrue(script.contains("aria-labelledby"))
        assertTrue(script.contains("e.labels"))
        assertTrue(script.contains("closest('label')"))
        assertTrue(script.contains("p.scopeNonce"))
        assertTrue(script.contains("candidates.slice(0,5)"))
        assertTrue(script.contains("const p = {"))
        assertTrue(script.contains("\"nonce\":\"fresh\""))
        assertFalse(script.contains("const p = \"{"))
        assertTrue(script.contains("Object.defineProperty(window,p.nonce"))
        assertTrue(script.contains("code:'INCOMPLETE_SCOPE'"))
        assertTrue(script.contains("\"partial\":false"))
        assertTrue(script.contains("const partialResult=p.partial&&!coverage.complete"))
        assertTrue(script.contains("const withPartial=value=>{if(partialResult)value.partial=true;return JSON.stringify(value);}"))
        assertTrue(script.contains("scopeCoverage:coverage,frameScopes:scopes"))
        assertTrue(script.contains("frameOrdinal:chosen.frameOrdinal"))
        assertTrue(script.contains("shadowDepth:chosen.descriptor.shadowRoots.length"))
        assertTrue(script.contains("coverage.scannedElements >= 5000"))
        assertTrue(script.contains("textNodesVisited>=20000"))
        assertTrue(script.contains("ref.frameWindow.document!==ref.document"))
        assertTrue(script.contains("frame.childWindow.document!==frame.childDocument"))
        assertFalse(script.contains("e.value"))
        assertFalse(script.contains(".textContent"))
        assertTrue(script.contains("INVALID_SELECTOR"))
        assertTrue(script.contains("aria-hidden"))
        assertTrue(script.contains("visibility==='collapse'"))
        assertFalse(script.contains("eval("))

        val markerValue = BrowserDomScript.locate(
            nonce = "marker", previousNonce = null, strategy = "css", value = "__LIMIT__",
            exact = true, name = null, scopeNonce = null, scopeIndex = null,
        )
        assertTrue(markerValue.contains("\"value\":\"__LIMIT__\""))
    }

    @Test
    fun locatePartialIsExplicitOptInDataOnly() {
        val script = BrowserDomScript.locate(
            nonce = "partial", previousNonce = null, strategy = "css", value = "#submit",
            exact = true, name = null, scopeNonce = null, scopeIndex = null, partial = true,
        )
        assertTrue(script.contains("\"partial\":true"))
        assertTrue(script.contains("if(!coverage.complete&&!p.partial)"))
        val parameters = JSONObject(Regex("const p = (\\{[^\\r\\n]*\\});").find(script)!!.groupValues[1])
        assertTrue(parameters.getBoolean("partial"))
        assertTrue(parameters.getString("value") == "#submit")
        assertTrue(script.contains("if(partialResult) return withPartial({code:'AMBIGUOUS'"))
        assertTrue(script.contains("Object.defineProperty(window,p.nonce"))
        assertFalse(script.contains("eval("))
    }

    @Test
    fun observeUsesTheProductionGeneratedScriptWithPrivacyAndBoundedCandidates() {
        val script = BrowserDomScript.observe(4096, "nonce-test", "previous-test")

        assertTrue(script.trim().startsWith("(() => {"))
        assertTrue(script.trim().endsWith("})()"))
        assertTrue(script.contains("document.readyState"))
        assertTrue(script.contains("coverage.scannedElements >= 5000"))
        assertTrue(script.contains("candidates.length>=80"))
        assertTrue(script.contains("e.getAttribute('role')||implicitRole(e)"))
        assertTrue(script.contains("aria-labelledby"))
        assertTrue(script.contains("e.labels"))
        assertTrue(script.contains("closest('label')"))
        assertTrue(script.contains("input,textarea,select,[contenteditable],[role=\"textbox\"]"))
        assertTrue(script.contains("[role=\"combobox\"],[role=\"spinbutton\"],[role=\"slider\"]"))
        assertFalse(script.contains(".value"))
        assertTrue(script.contains("previous-test"))
        assertTrue(script.contains("scopeCoverage,frameScopes,topFrameOnly:false,openShadowRootsSupported:true"))
        assertTrue(script.contains("closedShadowRootsSupported:false,crossOriginFramesSupported:false"))
        assertTrue(script.contains("frameOrdinal:candidate.frameOrdinal"))
        assertTrue(script.contains("shadowDepth:candidate.descriptor.shadowRoots.length"))
        assertTrue(script.contains("scopeCoverage.textNodesVisited >= 20000"))
    }

    @Test
    fun actionsGenerateExecutablePreflightForStateAndObstruction() {
        val click = BrowserDomScript.click("n", 0, "a")
        val type = BrowserDomScript.type("n", 0, "safe", true, "a")
        for (script in listOf(click, type)) {
            assertTrue(script.contains("isConnected"))
            assertTrue(script.contains("isVisible(e)"))
            assertTrue(script.contains("aria-disabled"))
        }
        assertTrue(click.contains("isObscured(e)"))
        assertTrue(type.contains("obscuredBy(e)"))
        assertTrue(type.contains("obscuredBy:obscuredMetadata(obscurer)"))
        assertTrue(type.contains("readOnly"))
        assertTrue(type.contains("aria-readonly"))
        assertTrue(click.contains("referenceStatus()"))
        assertTrue(click.contains("ref.document.elementFromPoint") == false)
        assertTrue(type.contains("ref.frameWindow.InputEvent"))
        assertTrue(click.contains(".authorize("))
        assertTrue(type.contains(".authorize("))
        assertFalse(click.contains(".authorizeVisual("))
        assertFalse(type.contains(".authorizeVisual("))
        assertTrue(click.contains("frame.parentDocument"))
        assertTrue(click.indexOf("scrollIntoView") < click.indexOf("isObscured(e)"))
        assertTrue(type.indexOf("scrollIntoView") < type.indexOf("obscuredBy(e)"))
    }

    @Test
    fun uploadActivationRequiresObservedTopDocumentFileInputOrExactVisibleLabelAndNeverDispatchesDomClick() {
        val script = BrowserDomScript.uploadActivationTarget("nonce", 3, "auth")
        assertTrue(script.contains("ref.document!==document"))
        assertTrue(script.contains("ref.frameChain.length!==0"))
        assertTrue(script.contains("e instanceof HTMLInputElement && e.type==='file'"))
        assertTrue(script.contains("e instanceof HTMLLabelElement && e.control instanceof HTMLInputElement && e.control.type==='file'"))
        assertTrue(script.contains("Array.from(e.control.labels).includes(e)"))
        assertTrue(script.contains("const hit=document.elementFromPoint(x,y)"))
        assertTrue(script.contains("isObscured(target)"))
        assertTrue(script.indexOf("isObscured(target)") < script.indexOf("code:'READY'"))
        assertTrue(script.contains("code:'READY'"))
        assertFalse(script.contains("e.click()"))
        assertFalse(script.contains("DataTransfer"))
    }
}

package com.dshbox.browser.runtime

import org.json.JSONObject

/** Executes bounded rendering policy reads and revision-checked updates without reloading pages. */
internal class BrowserRenderingCommand(
    private val read: () -> BrowserRenderingSettings,
    private val write: (BrowserRenderingSettings) -> Boolean,
    private val apply: (BrowserRenderingSettings) -> Unit,
) {
    fun execute(args: JSONObject, stillAuthorized: () -> Boolean): JSONObject {
        if (!stillAuthorized()) return result("SESSION_REVOKED", false, JSONObject())
        val current = try { read() } catch (_: Exception) {
            return result("SETTINGS_READ_FAILED", false, JSONObject())
        }
        if (args.optString("operation") == "get") return result("OK", false, data(current))
        if (args.optString("operation") != "set") return result("INVALID_ARGUMENT", false, JSONObject())
        val rawExpected = args.opt("expectedRevision")
        if (rawExpected !is Int && rawExpected !is Long) return result("INVALID_ARGUMENT", false, JSONObject())
        val expected = (rawExpected as Number).toLong()
        val key = args.optString("key")
        val value = if (key == BrowserRenderingSettings.KEY_TEXT_SCALE_PERCENT) {
            val rawValue = args.opt("value")
            if (rawValue !is Int && rawValue !is Long) return result("INVALID_ARGUMENT", false, JSONObject())
            val number = (rawValue as Number).toLong()
            if (number !in 50L..200L) return result("INVALID_ARGUMENT", false, JSONObject())
            number.toInt()
        } else args.opt("value")
        val change = try { current.update(key, value, expected) } catch (error: IllegalArgumentException) {
            return if (error.message == "STALE_REVISION") result("STALE_REVISION", false, data(current))
            else result("INVALID_ARGUMENT", false, JSONObject())
        }
        if (!change.changed) return result("OK", false, data(current))
        if (!stillAuthorized()) return result("SESSION_REVOKED", false, JSONObject())
        val persisted = runCatching { write(change.settings) }.getOrDefault(false)
        val effective = try { read() } catch (_: Exception) {
            return result("UNKNOWN", null, JSONObject().put("stage", "rendering_readback")
                .put("nextAction", "read_rendering_settings").put("automaticRetryAllowed", false))
        }
        val changed = effective != current
        if (changed) try { apply(effective) } catch (_: Exception) {
            return result("UNKNOWN", null, data(effective, true, persisted)
                .put("stage", "rendering_apply").put("nextAction", "inspect_page")
                .put("automaticRetryAllowed", false))
        }
        return if (persisted && effective == change.settings) {
            result("OK", true, data(effective, true, true))
        } else result("SETTINGS_PERSISTENCE_FAILED", null, data(effective, changed, false))
    }

    private fun data(settings: BrowserRenderingSettings, changed: Boolean = false, persisted: Boolean = false) =
        JSONObject().put("settings", JSONObject()
            .put("userAgentMode", settings.userAgentMode.wireValue)
            .put("layoutWidthMode", settings.layoutWidthMode.wireValue)
            .put("overviewMode", settings.overviewMode)
            .put("textScalePercent", settings.textScalePercent).put("revision", settings.revision))
            .put("changed", changed).put("reloadRequired", changed)
            .put("invalidatesVisualReferences", changed).put("persisted", persisted)

    private fun result(code: String, executed: Boolean?, data: JSONObject) = JSONObject()
        .put("code", code).put("executed", executed ?: JSONObject.NULL).put("data", data)
}

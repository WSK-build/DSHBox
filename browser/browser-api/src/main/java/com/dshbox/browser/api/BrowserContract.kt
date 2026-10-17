package com.dshbox.browser.api

object BrowserContract {
    const val ACTION_QUERY_STATUS = "com.dshbox.browser.action.QUERY_STATUS"
    const val ACTION_STATUS = "com.dshbox.browser.action.STATUS"
    const val ACTION_REVOKE_CONTROL = "com.dshbox.browser.action.REVOKE_CONTROL"
    const val ACTION_UI_SETTINGS = "com.dshbox.browser.action.UI_SETTINGS"
    const val EXTRA_STATUS = "browser_status"
    const val EXTRA_STATUS_JSON = "browser_status_json"
    const val EXTRA_RETURN_COMPONENT = "browser_return_component"
    const val EXTRA_LOCALE_TAG = "browser_locale_tag"
    const val EXTRA_THEME_MODE = "browser_theme_mode"
    const val EXTRA_OPEN_DSH_PAGE = "browser_open_dsh_page"
    const val STATUS_RUNNING = "running"
    const val STATUS_IDLE = "idle"
    const val STATUS_CLOSED = "closed"
    const val COMMAND_SERVICE_CLASS = "com.dshbox.browser.runtime.BrowserCommandService"
    const val MESSAGE_EXECUTE = 1
    const val MESSAGE_CANCEL = 2
    const val MESSAGE_RESULT = 3
    const val KEY_REQUEST_JSON = "browser.request.json"
    const val KEY_REQUEST_ID = "browser.request_id"
    const val KEY_RESPONSE_JSON = "browser.response.json"

    fun internalPermission(packageName: String): String =
        "$packageName.permission.BROWSER_INTERNAL"
}

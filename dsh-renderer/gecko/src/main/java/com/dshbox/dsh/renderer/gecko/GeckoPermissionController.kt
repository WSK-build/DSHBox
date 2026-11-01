package com.dshbox.dsh.renderer.gecko

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.dshbox.dsh.renderer.api.DshAsyncRequestId
import com.dshbox.dsh.renderer.api.DshOriginPolicy
import com.dshbox.dsh.renderer.api.DshRendererEvent
import com.dshbox.dsh.renderer.api.DshWebPermission
import com.dshbox.dsh.renderer.api.DshWebPermissionRequest
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

internal class GeckoPermissionController(
    private val context: Context,
    private val originPolicy: DshOriginPolicy,
    private val currentDocument: () -> String?,
    private val isActive: () -> Boolean,
    private val emit: (DshRendererEvent) -> Boolean,
    private val nextRequestId: () -> DshAsyncRequestId,
) : GeckoSession.PermissionDelegate {
    private var pending: PendingPermission? = null

    override fun onAndroidPermissionsRequest(
        session: GeckoSession,
        permissions: Array<String>?,
        callback: GeckoSession.PermissionDelegate.Callback,
    ) {
        requireMainThread("GeckoPermissionController.onAndroidPermissionsRequest")
        val requested = permissions.orEmpty()
        if (!isActive()) {
            callback.reject()
            return
        }
        val granted = requested.isEmpty() || requested.all { permission ->
            runCatching {
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        }
        if (granted) {
            callback.grant()
            return
        }
        val document = currentDocument()
        if (requested.toSet() != setOf(android.Manifest.permission.RECORD_AUDIO) ||
            document == null || !originPolicy.isTrusted(document)
        ) {
            callback.reject()
            return
        }
        cancelPending()
        val request = PendingAndroid(nextRequestId(), callback)
        pending = request
        if (!emit(DshRendererEvent.WebPermissionRequested(DshWebPermissionRequest(
                request.id, originOf(document), setOf(DshWebPermission.AUDIO_CAPTURE),
            )))) {
            pending = null
            callback.reject()
        }
    }

    override fun onContentPermissionRequest(
        session: GeckoSession,
        perm: GeckoSession.PermissionDelegate.ContentPermission,
    ): GeckoResult<Int> {
        requireMainThread("GeckoPermissionController.onContentPermissionRequest")
        if (!isActive() || !originPolicy.isTrusted(perm.uri)) {
            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
        }
        if (perm.value != GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT) {
            return GeckoResult.fromValue(perm.value)
        }

        cancelPending()
        val result = GeckoResult<Int>()
        val apiPermission = perm.permission.toApiPermission()
        val request = PendingContent(
            id = nextRequestId(),
            result = result,
            permission = apiPermission,
        )
        pending = request

        val event = DshWebPermissionRequest(
            requestId = request.id,
            origin = originOf(perm.uri),
            permissions = setOf(apiPermission),
        )
        if (!emit(DshRendererEvent.WebPermissionRequested(event))) {
            pending = null
            result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
        }
        return result
    }

    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        callback: GeckoSession.PermissionDelegate.MediaCallback,
    ) {
        requireMainThread("GeckoPermissionController.onMediaPermissionRequest")
        val requested = linkedSetOf<DshWebPermission>()
        if (!video.isNullOrEmpty()) requested += DshWebPermission.VIDEO_CAPTURE
        if (!audio.isNullOrEmpty()) requested += DshWebPermission.AUDIO_CAPTURE
        if (!isActive() || requested.isEmpty() || !originPolicy.isTrusted(uri)) {
            callback.reject()
            return
        }

        cancelPending()
        val request = PendingMedia(
            id = nextRequestId(),
            callback = callback,
            video = video?.firstOrNull(),
            audio = audio?.firstOrNull(),
        )
        pending = request

        val event = DshWebPermissionRequest(
            requestId = request.id,
            origin = originOf(uri),
            permissions = requested,
        )
        if (!emit(DshRendererEvent.WebPermissionRequested(event))) {
            pending = null
            callback.reject()
        }
    }

    fun complete(
        requestId: DshAsyncRequestId,
        grantedPermissions: Set<DshWebPermission>,
    ): Boolean {
        requireMainThread("GeckoPermissionController.complete")
        val request = pending?.takeIf { it.id == requestId } ?: return false
        pending = null

        when (request) {
            is PendingAndroid -> {
                if (DshWebPermission.AUDIO_CAPTURE in grantedPermissions &&
                    context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                ) request.callback.grant() else request.callback.reject()
            }
            is PendingContent -> {
                val value = if (request.permission in grantedPermissions) {
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                } else {
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                }
                request.result.complete(value)
            }

            is PendingMedia -> {
                val video = request.video.takeIf {
                    DshWebPermission.VIDEO_CAPTURE in grantedPermissions
                }
                val audio = request.audio.takeIf {
                    DshWebPermission.AUDIO_CAPTURE in grantedPermissions
                }
                if (video == null && audio == null) {
                    request.callback.reject()
                } else {
                    request.callback.grant(video, audio)
                }
            }
        }
        return true
    }

    fun cancelPending() {
        val request = pending ?: return
        pending = null
        when (request) {
            is PendingAndroid -> request.callback.reject()
            is PendingContent -> request.result.complete(
                GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY,
            )

            is PendingMedia -> request.callback.reject()
        }
    }

    fun release() {
        cancelPending()
    }

    private sealed interface PendingPermission {
        val id: DshAsyncRequestId
    }

    private class PendingAndroid(
        override val id: DshAsyncRequestId,
        val callback: GeckoSession.PermissionDelegate.Callback,
    ) : PendingPermission

    private class PendingContent(
        override val id: DshAsyncRequestId,
        val result: GeckoResult<Int>,
        val permission: DshWebPermission,
    ) : PendingPermission

    private class PendingMedia(
        override val id: DshAsyncRequestId,
        val callback: GeckoSession.PermissionDelegate.MediaCallback,
        val video: GeckoSession.PermissionDelegate.MediaSource?,
        val audio: GeckoSession.PermissionDelegate.MediaSource?,
    ) : PendingPermission
}

private fun Int.toApiPermission(): DshWebPermission =
    when (this) {
        GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION ->
            DshWebPermission.GEOLOCATION

        GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION ->
            DshWebPermission.NOTIFICATIONS

        GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS ->
            DshWebPermission.PROTECTED_MEDIA_ID

        else -> DshWebPermission.OTHER
    }

private fun originOf(raw: String): Uri? = runCatching {
    Uri.parse(raw)
        .buildUpon()
        .clearQuery()
        .fragment(null)
        .build()
}.getOrNull()

package com.dshbox.app.bridge.model

data class CommandRequest(
    val command: String,
    val args: List<String> = emptyList(),
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
    val stdin: String? = null,
    val timeoutMs: Long? = null,
    val runInBackground: Boolean = false,
)

data class CommandResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val processId: Long? = null,
)

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val modifiedAtMs: Long?,
)

data class FileContent(
    val path: String,
    val content: String,
)

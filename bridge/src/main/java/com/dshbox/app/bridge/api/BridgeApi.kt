package com.dshbox.app.bridge.api

import com.dshbox.app.bridge.model.CommandRequest
import com.dshbox.app.bridge.model.CommandResult
import com.dshbox.app.bridge.model.FileContent
import com.dshbox.app.bridge.model.FileEntry

interface BridgeApi {
    // Workspace
    suspend fun getCurrentWorkspace(): String
    suspend fun setCurrentWorkspace(path: String)
    suspend fun listWorkspaces(): List<String>
    suspend fun createWorkspace(path: String)

    // Filesystem (MVP: list/stat/select/open)
    suspend fun listDirectory(path: String): List<FileEntry>
    suspend fun stat(path: String): FileEntry
    suspend fun readText(path: String): FileContent
    suspend fun writeText(path: String, content: String)
    suspend fun createDirectory(path: String)
    suspend fun delete(path: String)
    suspend fun move(from: String, to: String)
    suspend fun copy(from: String, to: String)

    // Command
    suspend fun execute(request: CommandRequest): CommandResult
    suspend fun cancel(processId: Long)

    // Process
    suspend fun listProcesses(): List<Long>
    suspend fun killProcess(processId: Long)

    // Android
    suspend fun showNotification(title: String, body: String)
    suspend fun clipboardRead(): String
    suspend fun clipboardWrite(text: String)
}

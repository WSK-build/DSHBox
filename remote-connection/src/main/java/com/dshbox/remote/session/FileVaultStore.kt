package com.dshbox.remote.session

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [VaultStore] 的生产实现：模块私有目录下的密文文件存储。
 *
 * 文件内容一律为 [VaultCipher] 加密后的密文，明文不经此层落盘。
 *
 * 并发约定：同一进程内同一目录只存在一个实例（经 [of] 取用），写/删的互斥
 * 锁随之全局生效——宿主重建会话工厂会产生多个 [CredentialVault]，实例级锁
 * 无法跨工厂互斥，按目录路径登记单例消除该竞态。
 */
class FileVaultStore private constructor(baseDir: File) : VaultStore {

    private val dir: File = baseDir.apply { mkdirs() }

    /** 写/删串行化锁：同名条目的临时文件固定为 `name.tmp`，并发操作必须排队，避免互相截断或复活刚删条目。 */
    private val writeLock = Any()

    override fun read(name: String): ByteArray? {
        val file = File(dir, name)
        if (!file.isFile) return null
        return file.readBytes()
    }

    override fun write(name: String, data: ByteArray) {
        synchronized(writeLock) {
            val file = File(dir, name)
            val temp = File(dir, "$name.tmp")
            temp.writeBytes(data)
            if (!temp.renameTo(file)) {
                file.delete()
                check(temp.renameTo(file)) { "Failed to persist vault entry $name" }
            }
        }
    }

    override fun remove(name: String) {
        synchronized(writeLock) {
            File(dir, name).delete()
        }
    }

    companion object {

        /** 进程级目录路径 → 实例注册表。 */
        private val instances = ConcurrentHashMap<String, FileVaultStore>()

        /** 取 [baseDir] 对应的进程级单例：同目录复用同一实例，写互斥跨工厂生效。 */
        fun of(baseDir: File): FileVaultStore =
            instances.computeIfAbsent(baseDir.canonicalPath) { FileVaultStore(File(it)) }
    }
}

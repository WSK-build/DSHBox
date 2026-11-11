package com.dshbox.remote.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

/** 文件存档存储测试：读写删往返、同名条目并发操作完整性与进程级单例语义。 */
class FileVaultStoreTest {

    private fun newStore(): FileVaultStore =
        FileVaultStore.of(Files.createTempDirectory("file-vault-test").toFile())

    @Test fun writeThenReadReturnsIdenticalBytes() {
        val store = newStore()
        val data = ByteArray(256) { it.toByte() }
        store.write("entry", data)
        assertTrue(data.contentEquals(store.read("entry")))
    }

    @Test fun overwriteReplacesPreviousSnapshot() {
        val store = newStore()
        store.write("entry", "first".toByteArray())
        store.write("entry", "second".toByteArray())
        assertEquals("second", String(store.read("entry")!!))
    }

    @Test fun readMissingEntryReturnsNull() {
        assertNull(newStore().read("ghost"))
    }

    @Test fun removeDeletesEntry() {
        val store = newStore()
        store.write("entry", "data".toByteArray())
        store.remove("entry")
        assertNull(store.read("entry"))
    }

    @Test fun instancesAreInternedByDirectoryPath() {
        // 同目录全局唯一实例：写互斥随实例跨工厂生效（跨工厂复用同一 vault 目录）
        val dir = Files.createTempDirectory("file-vault-intern").toFile()
        assertTrue(FileVaultStore.of(dir) === FileVaultStore.of(dir))
        val other = Files.createTempDirectory("file-vault-intern-2").toFile()
        assertTrue(FileVaultStore.of(dir) !== FileVaultStore.of(other))
    }

    @Test fun concurrentWritesToSameEntryStayIntact() {
        // 同名条目并发写：写入串行化——读回结果必须是某一线程的完整快照，
        // 不允许出现交错截断的混合内容；工作线程异常计入断言
        val store = newStore()
        val threadCount = 8
        val payloadSize = 4096
        val payloads = (0 until threadCount).map { index ->
            ByteArray(payloadSize) { index.toByte() }
        }
        val errors = arrayOfNulls<Throwable>(threadCount)
        val startGate = CountDownLatch(1)
        val threads = payloads.mapIndexed { index, payload ->
            Thread {
                try {
                    startGate.await()
                    repeat(20) { store.write("shared", payload) }
                } catch (t: Throwable) {
                    errors[index] = t
                }
            }
        }
        threads.forEach { it.start() }
        startGate.countDown()
        threads.forEach { it.join() }

        assertTrue(errors.all { it == null })
        val readBack = store.read("shared")!!
        assertEquals(payloadSize, readBack.size)
        assertTrue(payloads.any { it.contentEquals(readBack) })
    }

    @Test fun removeUnderConcurrentWritesKeepsConsistentState() {
        // 删除与并发写同锁串行：最终状态只能是「已删」或「某线程的完整快照」，
        // 不允许复活被坏密文清理删掉的条目之外的中间态
        val store = newStore()
        val threadCount = 4
        val payloads = (0 until threadCount).map { index ->
            ByteArray(1024) { index.toByte() }
        }
        val errors = arrayOfNulls<Throwable>(threadCount + 1)
        val startGate = CountDownLatch(1)
        val writerThreads = payloads.mapIndexed { index, payload ->
            Thread {
                try {
                    startGate.await()
                    repeat(20) { store.write("shared", payload) }
                } catch (t: Throwable) {
                    errors[index] = t
                }
            }
        }
        val remover = Thread {
            try {
                startGate.await()
                repeat(20) { store.remove("shared") }
            } catch (t: Throwable) {
                errors[threadCount] = t
            }
        }
        (writerThreads + remover).forEach { it.start() }
        startGate.countDown()
        (writerThreads + remover).forEach { it.join() }

        assertTrue(errors.all { it == null })
        val readBack = store.read("shared")
        if (readBack != null) {
            assertTrue(payloads.any { it.contentEquals(readBack) })
        }
    }
}

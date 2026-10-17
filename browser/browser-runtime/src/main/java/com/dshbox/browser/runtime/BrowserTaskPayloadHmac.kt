package com.dshbox.browser.runtime

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Keyed request fingerprinting prevents offline guesses against low-entropy page text or form data. */
internal class BrowserTaskPayloadHmac(context: Context) {
    private val keyFile = AtomicFile(
        File(requireNotNull(context.applicationContext.getDatabasePath(DATABASE_NAME).parentFile), KEY_FILE_NAME),
    )

    fun digest(payload: ByteArray): String {
        val key = synchronized(KEY_LOCK) { loadOrCreateKey() }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(payload).toLowerHex()
    }

    private fun loadOrCreateKey(): ByteArray {
        val file = keyFile.baseFile
        val parent = requireNotNull(file.parentFile)
        check((parent.exists() || parent.mkdirs()) && parent.isDirectory) {
            "Unable to create Browser task journal directory"
        }
        if (file.exists() || File(file.path + ".bak").exists()) return keyFile.openRead().use { input ->
            val buffer = ByteArray(KEY_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                if (count == 0) {
                    val single = input.read()
                    if (single < 0) break
                    buffer[size++] = single.toByte()
                } else size += count
            }
            val bytes = buffer.copyOf(size)
            check(bytes.size == KEY_BYTES) { "Browser task HMAC key is invalid; refusing to rotate it" }
            bytes
        }
        val generated = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        var output: FileOutputStream? = null
        return try {
            output = keyFile.startWrite()
            output.write(generated)
            output.fd.sync()
            keyFile.finishWrite(output)
            output = null
            generated
        } catch (error: Throwable) {
            output?.let(keyFile::failWrite)
            throw IllegalStateException("Unable to persist Browser task HMAC key", error)
        }
    }

    private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
        for (byte in this@toLowerHex) {
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    private companion object {
        const val KEY_FILE_NAME = "browser-task-hmac.key"
        const val DATABASE_NAME = "browser-task-journal.db"
        const val KEY_BYTES = 32
        const val HEX = "0123456789abcdef"
        val KEY_LOCK = Any()
    }
}

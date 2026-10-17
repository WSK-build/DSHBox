package com.dshbox.browser.runtime

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Temporary external share target reads only a Browser-owned text artifact; sends nothing. */
class BrowserDownloadShareProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = runCatching {
            check(intent.action == Intent.ACTION_SEND)
            @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                ?: error("Missing shared artifact")
            check(uri.scheme == "content" && uri.authority == "com.dshbox.app.browser.download")
            val bytes = contentResolver.openInputStream(uri)!!.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 65_536)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            JSONObject().put("code", "OK").put("bytes", bytes.size)
                .put("text", bytes.toString(Charsets.UTF_8))
                .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        }.getOrElse { JSONObject().put("code", "FAILED").put("reason", it.javaClass.simpleName) }
        File(filesDir, "browser-download-share-proof-1016.json").writeText(result.toString())
        setContentView(TextView(this).apply { text = "Browser download check\n$result"; textSize = 18f })
    }
}

package com.dshbox.browser.runtime

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.security.MessageDigest
import java.net.URI
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Authenticates a served document's project/route, never its scripts, pixels or source lines. */
internal object BrowserProjectDocumentProof {
    private val servicePattern = Regex("[a-f0-9]{32}")
    private val fields = setOf("version", "serviceId", "projectId", "route", "issuedAtMillis", "expiresAtMillis", "documentNonce")
    data class Clock(val wallMillis: Long, val uptimeMillis: Long, val bootId: String)

    fun capture(filesRoot: File, marker: String, pageUrl: String): JSONObject? = runCatching {
        val payload = decode(marker) ?: return null
        val service = payload.optString("serviceId")
        if (!servicePattern.matches(service)) return null
        val record = readRecord(filesRoot, service) ?: return null
        val boot = File("/proc/sys/kernel/random/boot_id").bufferedReader().use { it.readLine()?.trim() }.orEmpty()
        val uptime = File("/proc/uptime").bufferedReader().use { it.readLine()?.substringBefore(' ')?.toDoubleOrNull() } ?: return null
        if (!uptime.isFinite() || uptime < 0 || boot.length !in 8..128) return null
        verify(marker, pageUrl, record, Clock(System.currentTimeMillis(), (uptime * 1000).toLong(), boot))
    }.getOrNull()

    fun verify(marker: String, pageUrl: String, record: JSONObject, clock: Clock): JSONObject? = runCatching {
        val p = decode(marker) ?: return null
        if (p.keys().asSequence().toSet() != fields || integral(p, "version") != 1L) return null
        val service = p.getString("serviceId")
        val route = p.getString("route")
        val project = p.getString("projectId")
        val nonce = p.getString("documentNonce")
        if (!servicePattern.matches(service) || !Regex("[a-f0-9]{24}").matches(project) ||
            !servicePattern.matches(nonce) || route.length !in 1..512 || !route.startsWith('/') ||
            route.any { it.code < 32 || it.code == 127 || it == '?' || it == '#' }) return null
        val issued = integral(p, "issuedAtMillis") ?: return null
        val expires = integral(p, "expiresAtMillis") ?: return null
        val leaseWall = integral(record, "leaseExpiresAtMillis") ?: return null
        val leaseUptime = integral(record, "leaseExpiresAtUptimeMillis") ?: return null
        if (clock.wallMillis < issued || clock.wallMillis >= expires || expires > leaseWall || expires <= issued ||
            clock.uptimeMillis < 0 || clock.uptimeMillis >= leaseUptime || clock.bootId != record.optString("bootId")) return null
        if (record.optInt("version") != 1 || record.optString("serviceId") != service ||
            record.optString("kind", "owned-static") != "owned-static") return null
        val root = record.getString("root")
        if (!root.startsWith("/root/projects/") || root.length > 1024 ||
            root.split('/').any { it == ".." || it == "." } || root.any { it.code < 32 || it.code == 127 }) return null
        if (hex(MessageDigest.getInstance("SHA-256").digest(root.toByteArray(Charsets.UTF_8))).take(24) != project) return null
        val url = URI(pageUrl)
        val port = record.getInt("port")
        if (port !in 1..65535 || url.scheme != "http" || url.host != "127.0.0.1" || url.userInfo != null ||
            url.port != port || (url.rawPath.ifEmpty { "/" }) != route) return null
        val token = record.getString("token")
        if (!Regex("[a-f0-9]{64}").matches(token)) return null
        val encoded = marker.substringBefore('.')
        val key = token.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val signature = marker.substringAfter('.').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (!MessageDigest.isEqual(mac.doFinal(encoded.toByteArray(Charsets.US_ASCII)), signature)) return null
        JSONObject().put("serviceId", service).put("projectId", project).put("kind", "owned-static")
            .put("origin", "http://127.0.0.1:$port").put("route", route)
            .put("projectRootRelative", root.removePrefix("/root/projects/"))
            .put("documentNonce", nonce).put("issuedAtMillis", issued).put("capturedAtMillis", clock.wallMillis)
            .put("relation", "authenticated_document_source").put("captureProvenanceVerified", true)
    }.getOrNull()

    private fun decode(marker: String): JSONObject? = runCatching {
        if (marker.length !in 67..2048 || !Regex("[A-Za-z0-9_-]+\\.[a-f0-9]{64}").matches(marker)) return null
        val bytes = Base64.getUrlDecoder().decode(marker.substringBefore('.'))
        if (bytes.size > 1400) return null
        JSONObject(String(bytes, Charsets.UTF_8))
    }.getOrNull()

    private fun integral(value: JSONObject, key: String): Long? {
        val n = value.opt(key)
        if (n !is Long && n !is Int) return null
        return (n as Number).toLong().takeIf { it in 0..9_007_199_254_740_991L }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Every directory is opened relative to a held handle; a replaced symlink cannot redirect this read. */
    private fun readRecord(root: File, service: String): JSONObject? {
        val absolute = root.toPath().toAbsolutePath().normalize()
        require(root.toPath().toAbsolutePath() == absolute)
        val opened = ArrayList<SecureDirectoryStream<java.nio.file.Path>>()
        try {
            val first = Files.newDirectoryStream(absolute.root)
            if (first !is SecureDirectoryStream<*>) { first.close(); return null }
            @Suppress("UNCHECKED_CAST")
            var directory = first as SecureDirectoryStream<java.nio.file.Path>
            opened.add(directory)
            for (part in absolute.map { it.toString() } + listOf("browser-tools", "run", "serve")) {
                directory = directory.newDirectoryStream(Paths.get(part), LinkOption.NOFOLLOW_LINKS)
                opened.add(directory)
            }
            val name = Paths.get("$service.json")
            val attrs = directory.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
            if (!attrs.isRegularFile || attrs.size() !in 1..16384) return null
            val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            return directory.newByteChannel(name, options).use { channel ->
                val bytes = ByteBuffer.allocate(attrs.size().toInt())
                while (bytes.hasRemaining()) if (channel.read(bytes) <= 0) return null
                if (channel.size() != attrs.size()) return null
                JSONObject(String(bytes.array(), Charsets.UTF_8))
            }
        } finally { opened.asReversed().forEach { runCatching { it.close() } } }
    }
}

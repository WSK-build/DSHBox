package com.dshbox.browser.runtime

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dshbox.browser.bridge.BrowserCliBroker
import com.dshbox.browser.api.BrowserClock
import com.dshbox.browser.api.BrowserLifecycleController
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Broker socket, separate Browser service process, and a deliberately unwritable mirror. */
@RunWith(AndroidJUnit4::class)
class BrowserBrokerReconnectAndroidTest {
    @Test fun brokerRestartRebuildsChannelAndQueriesHiddenRuntimeDespiteMirrorFailure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".test"))
        BrowserRuntimeSettings.preferences(context).edit().putBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, true).commit()
        val broker = BrowserCliBroker(context)
        val run = File(context.filesDir, "browser-tools/run")
        val mirror = File(run, "status.json")
        var generation: Long? = null
        var controlGeneration: Long? = null
        try {
            BrowserStatusPublisher(context, run).publish(
                BrowserLifecycleController(clock = BrowserClock { SystemClock.elapsedRealtime() }).snapshot(),
            )
            broker.start()
            val opened = request(run, "open")
            assertEquals(opened.toString(), "OK", opened.getString("code"))
            generation = opened.getLong("sessionGeneration")
            controlGeneration = opened.getLong("controlGeneration")
            val first = request(run, "status")
            assertEquals(generation, first.getLong("sessionGeneration"))
            val previousChannel = JSONObject(File(run, "channel.json").readText())
            assertTrue(!mirror.exists() || mirror.delete())
            assertTrue(mirror.mkdirs())
            File(mirror, "prevent-atomic-replace").writeText("test-owned blocker")
            broker.stop()
            broker.start()
            val nextChannel = JSONObject(File(run, "channel.json").readText())
            assertNotEquals(previousChannel.getString("socketName"), nextChannel.getString("socketName"))
            assertNotEquals(previousChannel.getString("token"), nextChannel.getString("token"))
            val deadline = SystemClock.elapsedRealtime() + 5_000
            var status = request(run, "status")
            while (status.optLong("sessionGeneration") != generation || status.optString("state") == "CLOSED") {
                assertTrue(status.toString(), SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(50)
                status = request(run, "status")
            }
            assertEquals("OK", status.getString("code"))
            assertEquals("IDLE", status.getString("state"))
            assertEquals("ai", status.getString("owner"))
            assertEquals(first.getLong("controlGeneration"), status.getLong("controlGeneration"))
            assertTrue(mirror.isDirectory)
            android.util.Log.i("BrowserBrokerProbe", "real_socket_reconnected_live_hidden_session:$generation")
        } finally {
            File(mirror, "prevent-atomic-replace").delete()
            if (mirror.isDirectory) mirror.delete()
            if (generation != null) runCatching { request(run, "close", generation, controlGeneration) }
            broker.close()
        }
    }

    private fun request(run: File, method: String, generation: Long? = null, controlGeneration: Long? = null): JSONObject {
        val channel = JSONObject(File(run, "channel.json").readText())
        val request = JSONObject().put("version", 1).put("requestId", UUID.randomUUID().toString())
            .put("method", method).put("deadlineAtMillis", System.currentTimeMillis() + 15_000)
            .put("args", JSONObject())
        if (generation != null) request.put("sessionGeneration", generation)
        if (method == "open") request.put("owner", "ai")
        if (controlGeneration != null) request.put("controlGeneration", controlGeneration).put("owner", "ai")
        val frame = JSONObject().put("version", 1).put("token", channel.getString("token"))
            .put("op", "request").put("request", request)
        return LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(channel.getString("socketName"), LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = 18_000
            socket.outputStream.write((frame.toString() + "\n").toByteArray(Charsets.UTF_8))
            socket.outputStream.flush()
            JSONObject(socket.inputStream.bufferedReader().readLine())
        }
    }
}

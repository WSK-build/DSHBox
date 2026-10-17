package com.dshbox.browser.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dshbox.browser.api.BrowserContract
import com.dshbox.browser.api.BrowserOwner
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BrowserStatusReconnectAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun reconnectQueryReturnsLiveHiddenSessionWhenMirrorCannotBeWritten() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".test"))
        val mirror = File(context.filesDir, "browser-tools/run/status.json")
        lateinit var gateway: BrowserSessionGateway
        var generation = 0L
        try {
            instrumentation.runOnMainSync {
                gateway = requireNotNull(BrowserSessionFactory.createOrReuse(context))
                generation = gateway.open(BrowserOwner.AI).sessionGeneration
            }
            instrumentation.waitForIdleSync()
            assertTrue(!mirror.exists() || mirror.delete())
            assertTrue(mirror.mkdirs())
            File(mirror, "prevent-atomic-replace").writeText("test-owned blocker")
            repeat(2) {
                val latch = CountDownLatch(1)
                val reply = AtomicReference<String>()
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        reply.set(intent.getStringExtra(BrowserContract.EXTRA_STATUS_JSON))
                        latch.countDown()
                    }
                }
                instrumentation.runOnMainSync {
                    val filter = IntentFilter(BrowserContract.ACTION_STATUS)
                    if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
                    context.sendBroadcast(Intent(BrowserContract.ACTION_QUERY_STATUS).setPackage(context.packageName),
                        BrowserContract.internalPermission(context.packageName))
                }
                try {
                    assertTrue("reconnected receiver did not obtain status", latch.await(5, TimeUnit.SECONDS))
                    val status = JSONObject(reply.get())
                    assertEquals(generation, status.getLong("sessionGeneration"))
                    assertEquals("ai", status.getString("owner"))
                    assertFalse(status.getBoolean("visible"))
                    assertEquals("idle", status.getString("state"))
                    assertTrue("fault must remain active", mirror.isDirectory)
                } finally { instrumentation.runOnMainSync { context.unregisterReceiver(receiver) } }
            }
        } finally {
            File(mirror, "prevent-atomic-replace").delete()
            if (mirror.isDirectory) mirror.delete()
            instrumentation.runOnMainSync { BrowserSessionGatewayRegistry.current()?.close() }
        }
    }
}

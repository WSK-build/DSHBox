package interlock.relay.core.sample

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import interlock.relay.core.runtime.RelayConfig
import interlock.relay.core.runtime.RelayRuntime
import interlock.relay.core.spi.RelayPathPolicy
import interlock.relay.core.spi.RelayPrefs
import interlock.relay.core.spi.RelayRedactor
import interlock.relay.core.spi.RelayText

/**
 * Minimal host wiring. This is the whole integration surface a host needs:
 * one call to [RelayRuntime.start] when the sandbox starts, one to
 * [RelayRuntime.stop] when it stops. Every override below is optional - drop
 * the argument to fall back to the core default.
 */
class SampleHostService : Service() {

    private val started = object : Any() {
        @Volatile
        var value = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(started) {
            if (!started.value) {
                RelayRuntime.start(applicationContext, sampleConfig(applicationContext))
                started.value = true
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        synchronized(started) {
            if (started.value) {
                RelayRuntime.stop()
                started.value = false
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /**
         * 全默认装配：不传任何覆盖项时，[RelayRuntime.start] 用 core 内建实现
         * （系统语言文案、无呈现面、内建能力全表、内建默认路径、SharedPreferences、恒等脱敏）。
         * 这里展示两处最常见的定制：路径策略与偏好文件名。
         */
        fun sampleConfig(context: Context): RelayConfig = RelayConfig(
            pathPolicy = object : RelayPathPolicy {
                override val guestEntry: String = "/opt/interlock-relay"
                override val hostDirName: String = "relay"
                override val cliName: String = "relay"
                override val artifactPrefix: String = "relay-"
                override val assetClient: String = "relay/relay-client.cjs"
                override val mediaAlbumDir: String = "Relay"
            },
            prefs = RelayPrefs.Default(context),
            text = RelayText.Default(context),
            redactor = RelayRedactor.IDENTITY,
        )
    }
}

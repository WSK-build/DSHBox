package interlock.relay.core.interlock

import android.Manifest
import android.os.Build
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.CapabilityRegistry
import interlock.relay.core.protocol.GrantGap
import interlock.relay.core.protocol.SystemGrantState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探测级用例：媒体读取「至少一类可读即可达」与设备状态「空运行期权限表即无门槛」
 * 两条口径，直接钉在 [SystemStateProbe.mediaProbe] / [SystemStateProbe.runtimeProbe]
 * 的纯判据上——设备构造探测器需要 Context，JVM 够不着；而能力清单的红点与真实调用
 * 是否对齐，靠的就是这两条判据。
 */
class SystemStateProbeMediaTest {

    private fun table(vararg granted: String): (String) -> Boolean = { it in granted.toSet() }

    // ── 媒体读取：至少一类可读即整条能力系统侧可达 ──

    @Test
    fun `缺音频权限时图片与视频仍可读且探测答 GRANTED`() {
        val granted = table(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        val probe = SystemStateProbe.mediaProbe(granted, Build.VERSION_CODES.TIRAMISU)
        assertEquals(SystemGrantState.GRANTED, probe.state)
        assertTrue(probe.gaps.isEmpty())
    }

    /** Android 14 只给「选定照片」的场景：图片/视频可读，探测不得报缺权限。 */
    @Test
    fun `仅选定照片时探测答 GRANTED`() {
        val granted = table(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val probe = SystemStateProbe.mediaProbe(granted, Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        assertEquals(SystemGrantState.GRANTED, probe.state)
        assertTrue(probe.gaps.isEmpty())
    }

    @Test
    fun `三类全缺时探测报 RUNTIME_ASKABLE 且缺口只含三类主权限`() {
        val probe = SystemStateProbe.mediaProbe(table(), Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        assertEquals(SystemGrantState.RUNTIME_ASKABLE, probe.state)
        // 替代候选（READ_MEDIA_VISUAL_USER_SELECTED）不进缺口清单：它不是必缺的一环。
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            ),
            probe.gaps.map { (it as GrantGap.RuntimePermission).permission },
        )
    }

    // ── 设备状态：运行期权限表为空即无门槛 ──

    @Test
    fun `设备状态探测答 GRANTED 且不依赖任何运行期权限`() {
        val descriptor = CapabilityRegistry.allDescriptors.single { it.id == CapabilityId.DEVICE_STATUS }
        val probe = SystemStateProbe.runtimeProbe(descriptor, table())
        assertEquals(SystemGrantState.GRANTED, probe.state)
        assertTrue(probe.gaps.isEmpty())
    }

    /** 对照组：运行期权限表非空且未授予时，同一条判据如实报缺——判据没有放水。 */
    @Test
    fun `运行期权限缺失时探测如实报缺`() {
        val descriptor = CapabilityRegistry.allDescriptors.single { it.id == CapabilityId.AUDIO_CAPTURE }
        val probe = SystemStateProbe.runtimeProbe(descriptor, table())
        assertEquals(SystemGrantState.RUNTIME_ASKABLE, probe.state)
        assertEquals(
            listOf(Manifest.permission.RECORD_AUDIO),
            probe.gaps.map { (it as GrantGap.RuntimePermission).permission },
        )
    }
}

package interlock.relay.core.exec.direct

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备状态读取的逐字段容错。设备读数在 JVM 上不可达，这里按 [DeviceStatus.Sources]
 * 的假读数穷举两类场景：字段齐全（一组数据一次给全），以及单字段抛错/空白——
 * 那一个字段落 null 或 "unknown"，其余字段照常返回，整条调用不失败。
 */
class DeviceStatusTest {

    private class FakeSources(
        val throwingKeys: Set<String> = emptySet(),
        val blankKeys: Set<String> = emptySet(),
        val plugged: Int = 1,
        /** 缺省给「充电中」，与接电缺省一致；个别用例显式给满电/放电看语义分家。 */
        val status: Int = android.os.BatteryManager.BATTERY_STATUS_CHARGING,
    ) : DeviceStatus.Sources {
        private fun <T> value(key: String, v: T): T {
            if (key in throwingKeys) throw IllegalStateException(key)
            if (key in blankKeys && v is String) @Suppress("UNCHECKED_CAST") return "" as T
            return v
        }

        override fun timeEpochMs(): Long = value("time", 1_700_000_000_000L)
        override fun timezoneId(): String = value("timezone", "Asia/Shanghai")
        override fun batteryLevel(): Int = value("battery", 80)
        override fun batteryPlugged(): Int = value("plugged", plugged)
        override fun batteryStatus(): Int = value("status", status)
        override fun screenOn(): Boolean = value("screenOn", true)
        override fun keyguardLocked(): Boolean = value("locked", false)
        override fun networkType(): String = value("networkType", "wifi")
        override fun networkConnected(): Boolean = value("connected", true)
        override fun streamVolume(stream: Int): Int = value("volume$stream", 7)
        override fun brightness(): Int = value("brightness", 120)
        override fun brightnessAuto(): Boolean = value("auto", false)
        override fun manufacturer(): String = value("manufacturer", "Acme")
        override fun model(): String = value("model", "Pixel")
        override fun androidVersion(): String = value("release", "14")
        override fun resolution(): String = value("resolution", "1080x2400")
        override fun language(): String = value("language", "zh-CN")
    }

    @Test
    fun `一次调用返回全部分组与字段`() {
        val json = DeviceStatus(FakeSources()).snapshot()
        assertEquals(
            setOf("time", "battery", "screen", "network", "volumes", "brightness", "device"),
            json.keys().asSequence().toSet(),
        )
        assertEquals(1_700_000_000_000L, json.getJSONObject("time").getLong("epochMs"))
        assertEquals("Asia/Shanghai", json.getJSONObject("time").getString("timezoneId"))
        val battery = json.getJSONObject("battery")
        assertEquals(80, battery.getInt("level"))
        assertEquals(true, battery.getBoolean("charging"))
        assertEquals("ac", battery.getString("chargingMethod"))
        val screen = json.getJSONObject("screen")
        assertEquals(true, screen.getBoolean("on"))
        assertEquals(false, screen.getBoolean("locked"))
        val network = json.getJSONObject("network")
        assertEquals("wifi", network.getString("type"))
        assertEquals(true, network.getBoolean("connected"))
        val volumes = json.getJSONObject("volumes")
        assertEquals(
            setOf("media", "ring", "notification", "alarm"),
            volumes.keys().asSequence().toSet(),
        )
        volumes.keys().asSequence().forEach { assertEquals(7, volumes.getInt(it)) }
        val device = json.getJSONObject("device")
        assertEquals(
            setOf("manufacturer", "model", "androidVersion", "resolution", "language"),
            device.keys().asSequence().toSet(),
        )
        assertEquals("zh-CN", device.getString("language"))
    }

    @Test
    fun `充电方式按接电类型映射且未接电时落 none`() {
        assertEquals("ac", DeviceStatus(FakeSources(plugged = 1)).snapshot().getJSONObject("battery").getString("chargingMethod"))
        // 未接电源的设备不会报「充电中」的状态位：两个读数按设备语义配套给。
        val unplugged = DeviceStatus(
            FakeSources(plugged = 0, status = android.os.BatteryManager.BATTERY_STATUS_DISCHARGING),
        ).snapshot().getJSONObject("battery")
        assertEquals("none", unplugged.getString("chargingMethod"))
        assertEquals(false, unplugged.getBoolean("charging"))
    }

    /** 「在充电」看状态位：「接着电源但充满」不算在充电，两种语义不能混成一个字段。 */
    @Test
    fun `charging 按状态位而不是接电与否`() {
        val full = DeviceStatus(
            FakeSources(
                plugged = 1,
                status = android.os.BatteryManager.BATTERY_STATUS_FULL,
            ),
        ).snapshot().getJSONObject("battery")
        assertEquals(false, full.getBoolean("charging"))
        assertEquals("ac", full.getString("chargingMethod"))
        val discharging = DeviceStatus(
            FakeSources(
                plugged = 0,
                status = android.os.BatteryManager.BATTERY_STATUS_DISCHARGING,
            ),
        ).snapshot().getJSONObject("battery")
        assertEquals(false, discharging.getBoolean("charging"))
        assertEquals("none", discharging.getString("chargingMethod"))
    }

    @Test
    fun `单字段抛错落 null 不拖垮其余字段`() {
        val json = DeviceStatus(
            FakeSources(
                throwingKeys = setOf("battery", "brightness", "volume3"),
            ),
        ).snapshot()
        // 抛错的字段是 null（JSON null），不是 0，也不是缺失的键。
        assertTrue(json.getJSONObject("battery").isNull("level"))
        assertTrue(json.getJSONObject("brightness").isNull("level"))
        assertTrue(json.getJSONObject("volumes").isNull("media"))
        // 同组的其余字段照常返回。
        assertEquals("ac", json.getJSONObject("battery").getString("chargingMethod"))
        assertTrue(json.getJSONObject("brightness").has("auto"))
        assertEquals(7, json.getJSONObject("volumes").getInt("ring"))
        // 其它分组完全不受影响。
        assertEquals("wifi", json.getJSONObject("network").getString("type"))
        assertEquals("Pixel", json.getJSONObject("device").getString("model"))
    }

    @Test
    fun `字符串字段读到空白落 unknown`() {
        val json = DeviceStatus(FakeSources(blankKeys = setOf("model"))).snapshot()
        assertEquals("unknown", json.getJSONObject("device").getString("model"))
        assertEquals("Acme", json.getJSONObject("device").getString("manufacturer"))
    }

    @Test
    fun `整组读数全部抛错时该组逐字段落空且不拖垮兄弟分组`() {
        val sources = object : DeviceStatus.Sources {
            override fun timeEpochMs(): Long = throw IllegalStateException("time")
            override fun timezoneId(): String = throw IllegalStateException("time")
            override fun batteryLevel(): Int = 50
            override fun batteryPlugged(): Int = 0
            override fun batteryStatus(): Int = android.os.BatteryManager.BATTERY_STATUS_DISCHARGING
            override fun screenOn(): Boolean = true
            override fun keyguardLocked(): Boolean = false
            override fun networkType(): String = "offline"
            override fun networkConnected(): Boolean = false
            override fun streamVolume(stream: Int): Int = 3
            override fun brightness(): Int = 60
            override fun brightnessAuto(): Boolean = true
            override fun manufacturer(): String = "Acme"
            override fun model(): String = "Pixel"
            override fun androidVersion(): String = "14"
            override fun resolution(): String = "1080x2400"
            override fun language(): String = "zh-CN"
        }
        val json = DeviceStatus(sources).snapshot()
        // 容错按字段走：数值字段落 null、字符串字段落 unknown，组内其余键照常在位。
        assertTrue(json.getJSONObject("time").isNull("epochMs"))
        assertEquals("unknown", json.getJSONObject("time").getString("timezoneId"))
        // 其它分组完全不受影响。
        assertEquals(50, json.getJSONObject("battery").getInt("level"))
        assertEquals("none", json.getJSONObject("battery").getString("chargingMethod"))
        assertEquals(false, json.getJSONObject("battery").getBoolean("charging"))
    }
}

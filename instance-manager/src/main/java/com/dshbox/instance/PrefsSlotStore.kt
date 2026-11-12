package com.dshbox.instance

import android.content.Context
import android.util.Log
import com.dshbox.app.common.Constants
import com.dshbox.app.common.DshInstance

/** 活跃槽位的 SharedPreferences 持久化；缺省/未知值回落主实例。 */
class PrefsSlotStore(context: Context) : SlotStore {

    private val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): DshInstance {
        val raw = prefs.getString(KEY_ACTIVE_INSTANCE, null)
        val instance = DshInstance.fromHomeLeaf(raw)
        if (raw != null && instance.homeLeaf != raw) {
            Log.w(TAG, "unknown persisted instance slot; falling back to main")
        }
        return instance
    }

    override fun write(instance: DshInstance) {
        // 槽位是崩溃恢复的关键小写入：commit 同步落盘，翻槽后进程被杀也不丢。
        prefs.edit().putString(KEY_ACTIVE_INSTANCE, instance.homeLeaf).commit()
    }

    companion object {
        const val KEY_ACTIVE_INSTANCE = "dsh_active_instance"
        private const val TAG = "PrefsSlotStore"
    }
}
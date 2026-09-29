package dshbox.adapter.surfaces

import android.graphics.Color

/**
 * 确认卡/提问卡共用的一套色值。
 *
 * 为什么单独一处：两张卡现在各有宿主（审批：悬浮窗与页内；提问：悬浮窗），而它们
 * "问的是同一件事"这层意思恰恰要求看起来是同一张卡。配色只在此定义一份、两处引用；
 * 各自定义就会出现两张不同的卡。
 */
internal object CardPalette {

    /**
     * 卡面底色：夜间半透明深色，白天半透明浅色。
     */
    fun cardColor(night: Boolean): Int = parse(if (night) "#D92F2F2F" else "#D9FFFFFF")

    /**
     * 正文与按钮文字。与墨色是同一个值（白天正文=墨色、夜间正文=反墨），
     * 因此这里**引用**墨色而不是再写一遍字面量：改了墨色而漏掉正文，两张卡当场分家。
     */
    fun textColor(night: Boolean): Int = if (night) inkDark else inkLight

    /** 次级文字（标题行、「全部驳回」）。 */
    fun subColor(night: Boolean): Int = parse(if (night) "#B4B4B4" else "#6B6B6B")

    /** 描边与分隔线。 */
    fun lineColor(night: Boolean): Int = parse(if (night) "#444444" else "#E5E5E5")

    /** 墨色（主按钮实底）。 */
    val inkLight: Int get() = parse("#171717")
    val inkDark: Int get() = parse("#F5F5F5")

    private fun parse(value: String): Int = Color.parseColor(value)
}

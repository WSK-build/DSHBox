package interlock.relay.core.exec.a11y

/**
 * 「以某控件为参照」的定位几何。
 *
 * 存在的理由：界面上大量控件不带 text、desc、viewId，类名也在整列里重复，绝对特征定位不到它们，
 * 助手只能在脚本里按屏幕坐标硬算 —— 而坐标逐帧量出来的一次偏差不会报错，它只会点到别的东西上。
 * 这里把那条推理收到一处：给定参照物与方位，取该方向上**最近**的候选，允许"第 N 个"。
 *
 * 判据只用节点的自报边界，不读任何厂商私有字段。单独成文件、且只吃整数盒子，是为了让这条
 * 判据在 JVM 上测得到：`AccessibilityNodeInfo` 在单测里是平台桩，拿它驱动几何等于没测。
 */
internal object RelativeGeometry {

    enum class Side { RIGHT, LEFT, ABOVE, BELOW }

    /** 一个候选的盒子。[ref] 是调用方认人的凭据（这里用树里的前序序号），本模块不理解它的含义。 */
    data class Box(
        val ref: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val className: String?,
    )

    sealed interface Outcome {
        data class Pick(val box: Box, val gapPx: Int) : Outcome
        /** 那个方向上一个候选都没有：与"歧义"是两件事，前者要换方位，后者要点名是哪一个。 */
        data object None : Outcome
        /** 并列最近、且调用方没有用 `nth` 指定第几个：不能静默取第一个。 */
        data class Ambiguous(val boxes: List<Box>, val gapPx: Int) : Outcome
    }

    /**
     * 排在同一距离上的容差（像素）。
     *
     * 一行里并排的同类控件，边界常有 1-2px 的出入；不给容差就会让"谁最近"取决于一次取整，
     * 而那正是本模块反复拒绝的那类假精确。
     */
    private const val TIE_PX = 2

    /**
     * 并列容差要随参照物的尺寸走：同一行两颗按钮在高密度屏上能量出好几像素的取整差，
     * 拿裸像素当容差，1440p/3x 屏上"同行相邻"会永不判并列而静默取近的那一颗。
     * 取参照物较长边的 1/20 作上限，让"同行小抖动"算并列，而"隔了一档间距"仍可区分。
     */
    private fun tieTolerance(anchor: Box): Int {
        val longSide = maxOf(anchor.right - anchor.left, anchor.bottom - anchor.top)
        return maxOf(TIE_PX, longSide / 20)
    }

    fun sideOf(value: String): Side? = when (value.lowercase()) {
        "right" -> Side.RIGHT
        "left" -> Side.LEFT
        "above", "up" -> Side.ABOVE
        "below", "down" -> Side.BELOW
        else -> null
    }

    /**
     * 取参照物某一侧上的第 [nth] 个候选。
     *
     * 排序分两段，顺序不能倒：**先看与参照物在跨轴上真的重叠那批**（正右方、正下方），
     * 一段里没有才退到"斜着但间隙更小"的那批。把间隙当唯一主键，会让"斜上方 gap=1"排在
     * "正右方 gap=5"之前 —— 那是往另一行的图标上点，而回包看着像成功。
     * 同一段内再按间隙、跨轴重叠量、前序序号排。
     *
     * 并列（间隙与跨轴重叠都落在容差内）时**无论 `nth` 是几**都回 [Outcome.Ambiguous]：
     * "第 2 个"表达的是序数，不是"这两个并列的你替我挑第二个"。用前序序号决胜等于把
     * 选择权交给一次布局重排。
     */
    fun pick(anchor: Box, candidates: List<Box>, side: Side, nth: Int, sameClass: Boolean): Outcome {
        val pool = candidates.filter { it.ref != anchor.ref }
            .filter { !sameClass || it.className == anchor.className }
            .filter { it.onSide(anchor, side) }
        if (pool.isEmpty()) return Outcome.None
        val aligned = pool.filter { it.crossOverlap(anchor, side) > 0 }
        val ranked = (if (aligned.isNotEmpty()) aligned else pool)
            .map { Ranked(it, it.gap(anchor, side), it.crossOverlap(anchor, side)) }
            .sortedWith(compareBy<Ranked> { it.gap }.thenBy { -it.cross }.thenBy { it.box.ref })
        val target = ranked.getOrNull(nth - 1) ?: return Outcome.None
        val tolerance = tieTolerance(anchor)
        val tied = ranked.filter {
            kotlin.math.abs(it.gap - target.gap) <= tolerance &&
                kotlin.math.abs(it.cross - target.cross) <= tolerance
        }
        if (tied.size > 1) return Outcome.Ambiguous(tied.map { it.box }, target.gap)
        return Outcome.Pick(target.box, target.gap)
    }

    private data class Ranked(val box: Box, val gap: Int, val cross: Int)

    /** 落在指定那一侧：不重叠，且在该轴之外。 */
    private fun Box.onSide(anchor: Box, side: Side): Boolean = when (side) {
        Side.RIGHT -> left >= anchor.right
        Side.LEFT -> right <= anchor.left
        Side.BELOW -> top >= anchor.bottom
        Side.ABOVE -> bottom <= anchor.top
    }

    private fun Box.gap(anchor: Box, side: Side): Int = when (side) {
        Side.RIGHT -> left - anchor.right
        Side.LEFT -> anchor.left - right
        Side.BELOW -> top - anchor.bottom
        Side.ABOVE -> anchor.top - bottom
    }

    /**
     * 与参照物在**垂直于方位**那根轴上的重叠像素。
     *
     * 左右方位看重叠行、上下方位看重叠列。为 0 的候选不是"同一行/同一列"，它排在后面：
     * 钟面上「时的右下方是分量」这类页里，正右方没有东西时应当拿斜下方那颗，而不是拿
     * 另一列里同样远但完全不在一行上的。
     */
    private fun Box.crossOverlap(anchor: Box, side: Side): Int = when (side) {
        Side.RIGHT, Side.LEFT -> minOf(bottom, anchor.bottom) - maxOf(top, anchor.top)
        Side.ABOVE, Side.BELOW -> minOf(right, anchor.right) - maxOf(left, anchor.left)
    }
}

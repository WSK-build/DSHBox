package interlock.relay.core.exec.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务枚举与搬迁的纯判据（`RelayShellService.TrustedTasks`）。
 * 解析错一个显示号就会把主屏的任务当成虚拟屏的任务（或反之），leaf→root 解析错会让
 * `am display move-stack` 搬错整棵任务栈，必须单独钉住。
 */
class TrustedTasksTest {

    /** 设备形状的 `dumpsys activity activities` 摘录：两块显示、虚拟屏上一个 RootTask 带叶子。 */
    private val dump = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #0 (activities from top to bottom):
          * Task{5df1a3e #12 type=standard A=1000:com.android.launcher U=0 visible=true mode=full}
            * ActivityRecord{a1 u0 com.android.launcher/.NestLauncher} t12}
        Display #45 (activities from top to bottom):
          * Task{5df1b7c #44 type=standard A=10364:com.ss.android.ugc.aweme U=0 visible=true mode=full}
            * Task{8ac12aa #703 type=standard A=10364:com.ss.android.ugc.aweme U=0 visible=true}
              * ActivityRecord{b2 u0 com.ss.android.ugc.aweme/.main.MainActivity} t703}
    """.trimIndent()

    @Test
    fun `按显示号取任务，虚拟屏之外的一律不进列表`() {
        val tasks = RelayShellService.TrustedTasks.parse(dump, 45)
        assertEquals(listOf(44, 703), tasks.map { it.taskId })
        // 主屏段里的 launcher 任务不该混进来。
        assertTrue(tasks.none { it.packageName == "com.android.launcher" })
        // 主屏枚举照常独立可用；launcher 不算真实应用任务，被过滤后为空。
        val main = RelayShellService.TrustedTasks.parse(dump, 0)
        assertTrue(main.isEmpty())
    }

    @Test
    fun `缩进最浅的是 RootTask，叶子认最近的更浅任务为根`() {
        val tasks = RelayShellService.TrustedTasks.parse(dump, 45)
        val root = tasks.first { it.taskId == 44 }
        val leaf = tasks.first { it.taskId == 703 }
        assertEquals(44, root.rootTaskId)
        assertEquals(44, leaf.rootTaskId)
    }

    @Test
    fun `包名优先取行内 A=，RootTask 缺 A 时落回辖区第一条活动的包名`() {
        val tasks = RelayShellService.TrustedTasks.parse(dump, 45)
        val root = tasks.first { it.taskId == 44 }
        val leaf = tasks.first { it.taskId == 703 }
        assertEquals("com.ss.android.ugc.aweme", root.packageName)
        assertEquals("com.ss.android.ugc.aweme", leaf.packageName)
        // 叶子的顶层活动短名来自第一条 ActivityRecord。
        assertEquals(".main.MainActivity", leaf.activity)
    }

    @Test
    fun `空输出与没有该显示段时回空列表，负 displayId 不解析`() {
        assertTrue(RelayShellService.TrustedTasks.parse("", 45).isEmpty())
        assertTrue(RelayShellService.TrustedTasks.parse("nothing useful", 45).isEmpty())
        assertTrue(RelayShellService.TrustedTasks.parse(dump, -1).isEmpty())
    }

    /**
     * 内部枚举只留真实应用任务 —— home 根任务、launcher、宿主
     * `com.dshbox.app` 与 systemui 一律排除，避免搬 home/launcher 根任务导致虚拟屏
     * 变形，或搬宿主自家 App 导致它重启。
     */
    @Test
    fun `home launcher 宿主 systemui 任务被排除，普通应用任务保留`() {
        val mixed = """
            Display #45 (activities from top to bottom):
              * Task{h1 #40 type=home A=1000:com.android.launcher3 U=0 visible=true}
                * ActivityRecord{x1 u0 com.android.launcher3/.NestLauncher} t40}
              * Task{r1 #41 type=standard A=10364:com.ss.android.ugc.aweme U=0 visible=true}
                * ActivityRecord{x2 u0 com.ss.android.ugc.aweme/.main.MainActivity} t41}
              * Task{l1 #42 type=standard A=1000:com.android.launcher U=0}
              * Task{d1 #43 type=standard A=10364:com.dshbox.app U=0}
                * ActivityRecord{x3 u0 com.dshbox.app/.MainActivity} t43}
              * Task{s1 #44 type=standard A=1000:com.android.systemui U=0}
              * Task{n1 #45 type=standard A=10102:com.example.reader U=0}
                * ActivityRecord{x4 u0 com.example.reader/.ReaderActivity} t45}
              * Task{h2 #46 type=home U=0}
        """.trimIndent()
        val tasks = RelayShellService.TrustedTasks.parse(mixed, 45)
        // 只剩两条真实应用任务；home 根（40）被排除不影响 41/45 的 root 判定（各自是 root）。
        assertEquals(listOf(41, 45), tasks.map { it.taskId })
        assertEquals(listOf(41, 45), tasks.map { it.rootTaskId })
        assertEquals("com.ss.android.ugc.aweme", tasks.first().packageName)
        assertEquals(".main.MainActivity", tasks.first().activity)
        // 46 没有行内 A= 与 ActivityRecord：按 `type=home` 排除（type 是唯一稳定记号）。
        assertTrue(tasks.none { it.taskId == 46 })
    }

    @Test
    fun `SecondaryDisplayLauncher 等活动名含 launcher 的任务按活动名排除`() {
        val dump2 = """
            Display #45 (activities from top to bottom):
              * Task{q1 #50 type=standard A=1000:com.example.shell U=0}
                * ActivityRecord{y1 u0 com.example.shell/.SecondaryDisplayLauncher} t50}
              * Task{q2 #51 type=standard A=10102:com.example.notes U=0}
                * ActivityRecord{y2 u0 com.example.notes/.NotesActivity} t51}
        """.trimIndent()
        val tasks = RelayShellService.TrustedTasks.parse(dump2, 45)
        assertEquals(listOf(51), tasks.map { it.taskId })
        // isAppTask 纯判据直测：包名与活动名两条路都算。
        assertFalse(
            RelayShellService.TrustedTasks.isAppTask(
                RelayShellService.TrustedTasks.Entry(50, "com.example.shell", ".SecondaryDisplayLauncher", 50),
            ),
        )
        assertTrue(
            RelayShellService.TrustedTasks.isAppTask(
                RelayShellService.TrustedTasks.Entry(51, "com.example.notes", ".NotesActivity", 51),
            ),
        )
    }

    @Test
    fun `缩进齐平的 ROM：每条任务都按 root 处理`() {
        val flat = """
            Display #7 (activities from top to bottom):
            * Task{aa #31 type=standard A=10364:com.a/.x}
            * Task{bb #32 type=standard A=10364:com.b/.y}
        """.trimIndent()
        val tasks = RelayShellService.TrustedTasks.parse(flat, 7)
        assertEquals(listOf(31, 32), tasks.map { it.taskId })
        assertEquals(listOf(31, 32), tasks.map { it.rootTaskId })
    }

    @Test
    fun `rootOf：root 原样、叶子换 RootTask 号、陌生任务回 null`() {
        val tasks = RelayShellService.TrustedTasks.parse(dump, 45)
        assertEquals(44, RelayShellService.TrustedTasks.rootOf(44, tasks))
        assertEquals(44, RelayShellService.TrustedTasks.rootOf(703, tasks))
        assertNull(RelayShellService.TrustedTasks.rootOf(999, tasks))
    }

    @Test
    fun `tasksJson 摊平任务数组，parseTasksJson 回读保留 root 号`() {
        val tasks = RelayShellService.TrustedTasks.parse(dump, 45)
        val json = RelayShellService.TrustedTasks.tasksJson(45, tasks)
        assertTrue(json.contains("\"displayId\":45"))
        assertTrue(json.contains("\"taskId\":703"))
        assertTrue(json.contains("\"rootTaskId\":44"))
        assertTrue(json.contains("\"package\":\"com.ss.android.ugc.aweme\""))
        // label 占位值就是 package（宿主侧再映射应用标签）。
        assertTrue(json.contains("\"label\":\"com.ss.android.ugc.aweme\""))
        val back = RelayShellService.TrustedTasks.parseTasksJson(json)
        assertEquals(tasks.map { it.taskId }, back.map { it.taskId })
        assertEquals(tasks.map { it.rootTaskId }, back.map { it.rootTaskId })
    }

    @Test
    fun `parseTasksJson 对畸形正文按空列表处理，不抛`() {
        assertTrue(RelayShellService.TrustedTasks.parseTasksJson("").isEmpty())
        assertTrue(RelayShellService.TrustedTasks.parseTasksJson("not json").isEmpty())
        assertTrue(RelayShellService.TrustedTasks.parseTasksJson("{\"tasks\":[]}").isEmpty())
    }

    @Test
    fun `panelMoveEndpoints：main 是虚拟屏→0，virtual 是 0→虚拟屏`() {
        assertEquals(42 to 0, ShizukuBackend.panelMoveEndpoints(sourceIsVirtual = true, virtualDisplayId = 42))
        assertEquals(0 to 42, ShizukuBackend.panelMoveEndpoints(sourceIsVirtual = false, virtualDisplayId = 42))
    }

    /**
     * 设备格式：任务行 `* Task{`（星号后有空格）、顶层活动行
     * `topResumedActivity=ActivityRecord{`。原有解析只认 `*Task{`/`*ActivityRecord{`，
     * 设备上任务列表永远为空 —— 本条用真实格式钉死回归。
     */
    private val realDeviceDump = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #0 (activities from top to bottom):
          topResumedActivity=ActivityRecord{5df1a3e u0 com.android.launcher/.NestLauncher} t12}
        Display #45 (activities from top to bottom):
          * Task{5df1b7c #44 type=standard A=10364:com.ss.android.ugc.aweme U=0 visible=true mode=full}
            * Task{8ac12aa #703 type=standard A=10364:com.ss.android.ugc.aweme U=0 visible=true}
              * topResumedActivity=ActivityRecord{b2 u0 com.ss.android.ugc.aweme/.main.MainActivity} t703}
    """.trimIndent()

    @Test
    fun `真机格式（星号后有空格 + topResumedActivity）能解析出任务与包名`() {
        val tasks = RelayShellService.TrustedTasks.parse(realDeviceDump, 45)
        assertEquals("真机格式的任务行必须被认出（P0：此前永远为空）", listOf(44, 703), tasks.map { it.taskId })
        // 叶子 703 认最近的更浅任务（44）为根 —— rootTaskId 是 move-stack 的参数语义。
        assertEquals(44 to 44, tasks.first().rootTaskId to tasks.last().rootTaskId)
        assertEquals("包名从 A= 取", "com.ss.android.ugc.aweme", tasks.first().packageName)
        assertEquals("顶层活动短名从 topResumedActivity= 取", ".main.MainActivity", tasks.last().activity)
        // 主屏段不混入
        assertTrue(tasks.none { it.packageName == "com.android.launcher" })
    }

    @Test
    fun `orientSize：landscape 竖改横、portrait 横改竖、齐平与缺省不动`() {
        // landscape：竖屏（w<h）交换
        assertEquals(1032 to 720, ShizukuBackend.orientSize(720, 1032, "landscape"))
        // landscape：已是横屏不动
        assertEquals(1032 to 720, ShizukuBackend.orientSize(1032, 720, "landscape"))
        // portrait：横屏（w>h）交换
        assertEquals(720 to 1032, ShizukuBackend.orientSize(1032, 720, "portrait"))
        // portrait：已是竖屏不动
        assertEquals(720 to 1032, ShizukuBackend.orientSize(720, 1032, "portrait"))
        // 大小写不敏感
        assertEquals(1032 to 720, ShizukuBackend.orientSize(720, 1032, "LandScape"))
        // 齐平不交换
        assertEquals(800 to 800, ShizukuBackend.orientSize(800, 800, "landscape"))
        assertEquals(800 to 800, ShizukuBackend.orientSize(800, 800, "portrait"))
        // 缺省（null）不交换
        assertEquals(720 to 1032, ShizukuBackend.orientSize(720, 1032, null))
    }

    /**
     * 真实覆盖"任务行没有 `A=`、包名只能靠辖区 ActivityRecord
     * 回填"的场景 —— 旧实现的 `groupValues[4]` 未 `ifEmpty { null }`，空串冒充包名，
     * 回填失效、过滤对宿主/systemui/launcher 全部失手。
     */
    private val backfillDump = """
        Display #45 (activities from top to bottom):
          * Task{aa #80 type=standard U=0 visible=true}
            * topResumedActivity=ActivityRecord{c1 u0 com.dshbox.app/.MainActivity} t80}
          * Task{bb #81 type=standard U=0 visible=true}
            * topResumedActivity=ActivityRecord{c2 u0 com.example.real/.MainActivity} t81}
    """.trimIndent()

    @Test
    fun `无 A= 的任务按辖区 ActivityRecord 回填包名，回填后过滤照常生效`() {
        val tasks = RelayShellService.TrustedTasks.parse(backfillDump, 45)
        // 宿主包（回填所得）被过滤；真实应用保留且包名来自 ActivityRecord。
        assertEquals(listOf(81), tasks.map { it.taskId })
        assertEquals("com.example.real", tasks.first().packageName)
        assertEquals(".MainActivity", tasks.first().activity)
    }

    /**
     * 被排除 root 的整棵子树一并丢弃 —— 否则叶子存在被排除的
     * root 号，`panelTaskMove` 搬叶子时 `rootOf` 会把 move-stack 打到 launcher/home 根。
     */
    @Test
    fun `被排除 root（launcher、home）的叶子整棵丢弃，不会搬被删 root`() {
        val subtree = """
            Display #45 (activities from top to bottom):
              * Task{l1 #60 type=standard A=1000:com.android.launcher U=0 visible=true}
                * Task{g1 #61 type=standard A=10364:com.example.game U=0 visible=true}
                  * ActivityRecord{z1 u0 com.example.game/.GameActivity} t61}
              * Task{h1 #62 type=home U=0}
                * Task{r1 #63 type=standard A=10102:com.example.reader U=0}
                  * ActivityRecord{z2 u0 com.example.reader/.ReaderActivity} t63}
              * Task{s1 #64 type=standard A=10102:com.example.notes U=0}
                * ActivityRecord{z3 u0 com.example.notes/.NotesActivity} t64}
        """.trimIndent()
        val tasks = RelayShellService.TrustedTasks.parse(subtree, 45)
        // launcher 根（60）下的 game 叶（61）、home 根（62）下的 reader 叶（63）整棵丢弃；
        // 独立的真实应用根（64）保留。
        assertEquals(listOf(64), tasks.map { it.taskId })
        // 被丢叶子的 root 号绝不会再被 move-stack 搬走。
        assertNull(RelayShellService.TrustedTasks.rootOf(61, tasks))
        assertNull(RelayShellService.TrustedTasks.rootOf(63, tasks))
    }

    /** 设备格式（`* Task{` + `topResumedActivity=`）下 type/home/A= 组捕获。 */
    @Test
    fun `真机格式下 type、home、A= 组捕获正确`() {
        val dump2 = """
            Display #45 (activities from top to bottom):
              * Task{aa #90 type=home A=1000:com.android.launcher3 U=0 visible=true}
                * topResumedActivity=ActivityRecord{m1 u0 com.android.launcher3/.NestLauncher} t90}
              * Task{bb #91 type=standard A=10364:com.example.real U=0 visible=true}
                * topResumedActivity=ActivityRecord{m2 u0 com.example.real/.main.MainActivity} t91}
        """.trimIndent()
        val tasks = RelayShellService.TrustedTasks.parse(dump2, 45)
        // home 根被 type 过滤；standard 任务连 type/包名/活动一并读全。
        assertEquals(listOf(91), tasks.map { it.taskId })
        assertEquals("standard", tasks.first().taskType)
        assertEquals("com.example.real", tasks.first().packageName)
        assertEquals(".main.MainActivity", tasks.first().activity)
    }

    @Test
    fun `tasksJson 带出 taskType，parseTasksJson 回读保留；缺键回 null`() {
        val json = RelayShellService.TrustedTasks.tasksJson(
            45,
            listOf(
                RelayShellService.TrustedTasks.Entry(703, "com.example.real", ".MainActivity", 703, "standard"),
            ),
        )
        assertTrue(json.contains("\"taskType\":\"standard\""))
        val back = RelayShellService.TrustedTasks.parseTasksJson(json)
        assertEquals("standard", back.first().taskType)
        // 旧回包（无 taskType 键）按 null 处理，不抛。
        val legacy = RelayShellService.TrustedTasks.parseTasksJson(
            """{"displayId":45,"tasks":[{"taskId":9,"package":"com.a","label":"com.a","activity":".M","rootTaskId":9}]}""",
        )
        assertEquals(listOf(9), legacy.map { it.taskId })
        assertEquals(null, legacy.first().taskType)
    }

    @Test
    fun `orientationError：缺省合法，非白名单值回 malformed 短句`() {
        val ok = org.json.JSONObject("{}")
        val blank = org.json.JSONObject("{\"orientation\":\"\"}")
        val good = org.json.JSONObject("{\"orientation\":\"Portrait\"}")
        val bad = org.json.JSONObject("{\"orientation\":\"diagonal\"}")
        assertNull(ShizukuBackend.orientationError(ok))
        assertNull(ShizukuBackend.orientationError(blank))
        // 大小写不敏感（JUnit4 assertNull 无 message 形参，单独成行）
        assertNull(ShizukuBackend.orientationError(good))
        val error = ShizukuBackend.orientationError(bad)
        assertEquals("diagonal 不是 portrait/landscape", true, error != null && error.contains("portrait"))
    }

    @Test
    fun `moveSucceeded：退出码 0 且无平台异常字样才算成`() {
        assertTrue(RelayShellService.TrustedTasks.moveSucceeded(0, "", ""))
        // `am` 把"任务不存在"写在输出里而退出码仍可能给 0：两侧都要过。
        assertFalse(RelayShellService.TrustedTasks.moveSucceeded(0, "Unknown rootTaskId: 123", ""))
        assertFalse(RelayShellService.TrustedTasks.moveSucceeded(0, "", "java.lang.Exception: move failed"))
        assertFalse(RelayShellService.TrustedTasks.moveSucceeded(1, "", "Error: could not move stack"))
    }

    /**
     * main 方向搬迁成功后的 windowing mode 校正命令 —— 优先
     * `am start --windowingMode 1 --display 0 --task <root>`，兜底
     * `wm set-display-windowing-mode -d 0 1`；非法 rootTaskId 回空列表。
     */
    @Test
    fun `windowingFixCommands：am start 优先、wm 兜底、负号回空`() {
        assertEquals(
            listOf(
                "am start --windowingMode 1 --display 0 --task 44",
                "wm set-display-windowing-mode -d 0 1",
            ),
            RelayShellService.TrustedTasks.windowingFixCommands(44),
        )
        assertTrue(RelayShellService.TrustedTasks.windowingFixCommands(-1).isEmpty())
    }
}

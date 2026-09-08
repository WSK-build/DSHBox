package com.dshbox.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 1.2.0 §10.1：MovePlanner + layerOf 纯 JVM 单测。
 *
 * 覆盖：防环（自身/子孙）、无操作判定、冲突预演（文件/目录/互冲）、决策解析
 * （OVERWRITE/SKIP/RENAME 同名计数）、layerOf 全组合 + 层根 null 语义、
 * `.dsh` 段匹配嵌套三形态（源/目标/路径中间段）、系统目录拒绝。
 */
class MovePlannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- layerOf：全组合 ----------

    private fun roots(
        sandbox: File,
        workspace: File,
        node: File? = null,
        dsh: File? = null,
    ): LayerRoots = LayerRoots(sandbox, workspace, node, dsh)

    @Test
    fun layerOfWorkspaceAndBase() {
        val sandbox = tmp.newFolder("sandbox")
        val workspace = tmp.newFolder("user-data")
        val r = roots(sandbox, workspace)
        assertEquals(Layer.WORKSPACE, layerOf(File(workspace, "a.txt").absolutePath, r))
        assertEquals(Layer.WORKSPACE, layerOf(workspace.absolutePath, r))
        assertEquals(Layer.BASE, layerOf(File(sandbox, "etc/passwd").absolutePath, r))
        assertEquals(Layer.BASE, layerOf(sandbox.absolutePath, r))
    }

    @Test
    fun layerOfNodeAndDshPrefixes() {
        val sandbox = tmp.newFolder("sandbox2")
        val workspace = tmp.newFolder("user-data2")
        val node = tmp.newFolder("node")
        val dsh = tmp.newFolder("dsh")
        val r = roots(sandbox, workspace, node, dsh)
        assertEquals(Layer.NODE, layerOf(node.absolutePath, r))
        assertEquals(Layer.NODE, layerOf(File(node, "lib/module.js").absolutePath, r))
        assertEquals(Layer.DSH, layerOf(dsh.absolutePath, r))
        assertEquals(Layer.DSH, layerOf(File(dsh, "lib/bin.js").absolutePath, r))
    }

    @Test
    fun layerOfTreatsUninstalledLayersAsBase() {
        val sandbox = tmp.newFolder("sandbox3")
        val workspace = tmp.newFolder("user-data3")
        // 层未安装（null）：即使路径字符串位于「假想层根」下也按 BASE 处理
        val r = roots(sandbox, workspace, node = null, dsh = null)
        assertEquals(Layer.BASE, layerOf(File(sandbox, "usr/local/x").absolutePath, r))
    }

    @Test
    fun layerOfSegmentMatchAnyDepth() {
        val sandbox = tmp.newFolder("sandbox4")
        val workspace = tmp.newFolder("user-data4")
        val r = roots(sandbox, workspace)
        // 系统目录段匹配：任意深度
        assertEquals(Layer.SYSTEM_DIR, layerOf(File(sandbox, "proc").absolutePath, r))
        assertEquals(Layer.SYSTEM_DIR, layerOf(File(sandbox, "etc/tmp/deep/file").absolutePath, r))
        // 同名前缀的不同段不应命中（systemd ≠ system）
        assertEquals(Layer.BASE, layerOf(File(sandbox, "var/systemd").absolutePath, r))
        // .dshbox 属系统目录名单
        assertEquals(Layer.SYSTEM_DIR, layerOf(File(sandbox, ".dshbox/layer.sha256").absolutePath, r))
    }

    @Test
    fun layerOfDshDataSegmentMatch() {
        val sandbox = tmp.newFolder("sandbox5")
        val workspace = tmp.newFolder("user-data5")
        val r = roots(sandbox, workspace)
        // 顶层 .dsh
        assertEquals(Layer.DSH_DATA, layerOf(File(workspace, ".dsh").absolutePath, r))
        // 嵌套 .dsh（1.1.1 protectedRiskLevel 漏判、1.2.0 修复的场景）
        assertEquals(Layer.DSH_DATA, layerOf(File(workspace, "foo/.dsh/secret.txt").absolutePath, r))
        // .dshbox 属系统目录名单
        assertEquals(Layer.SYSTEM_DIR, layerOf(File(sandbox, ".dshbox/layer.sha256").absolutePath, r))
    }

    // ---------- 防环 ----------

    private fun plan(sources: List<File>, target: File, roots: LayerRoots) = MovePlanner.planMove(
        MoveRequest(sources = sources, targetDir = target, roots = roots),
    )

    @Test
    fun movingDirOntoItselfIsIllegalCycle() {
        val dir = tmp.newFolder("self")
        val r = roots(tmp.newFolder("sb"), tmp.newFolder("ws"))
        val p = plan(listOf(dir), dir, r)
        assertEquals(1, p.issues.count { it.kind == IssueKind.ILLEGAL_CYCLE })
        assertTrue(p.items.isEmpty())
    }

    @Test
    fun movingDirIntoOwnChildIsIllegalCycle() {
        val parent = tmp.newFolder("parent")
        val child = File(parent, "child").also { it.mkdirs() }
        val r = roots(tmp.newFolder("sb2"), tmp.newFolder("ws2"))
        val p = plan(listOf(parent), child, r)
        assertEquals(1, p.issues.count { it.kind == IssueKind.ILLEGAL_CYCLE })
    }

    @Test
    fun movingFileOntoSamePathIsIllegalCycle() {
        val f = tmp.newFile("same.txt")
        val r = roots(tmp.newFolder("sb3"), tmp.newFolder("ws3"))
        val p = plan(listOf(f), f, r)
        assertEquals(1, p.issues.count { it.kind == IssueKind.ILLEGAL_CYCLE })
    }

    // ---------- 无操作 ----------

    @Test
    fun movingToOwnParentIsNoOp() {
        val parent = tmp.newFolder("noop-parent")
        val f = File(parent, "child.txt").also { it.writeText("x") }
        val r = roots(tmp.newFolder("sb4"), tmp.newFolder("ws4"))
        val p = plan(listOf(f), parent, r)
        assertEquals(1, p.issues.count { it.kind == IssueKind.NO_OP })
        assertTrue(p.items.isEmpty())
    }

    // ---------- 冲突预演 ----------

    @Test
    fun sameNameFileConflictIsReportedWhenUndecided() {
        val src = tmp.newFile("a.txt").also { it.writeText("src") }
        val target = tmp.newFolder("target1")
        File(target, "a.txt").writeText("dst")
        val p = plan(listOf(src), target, roots(tmp.newFolder("sb5"), tmp.newFolder("ws5")))
        val conflict = p.issues.singleOrNull { it.kind == IssueKind.CONFLICT_FILE }
        assertNotNull(conflict)
        assertTrue(p.items.isEmpty())
    }

    @Test
    fun sameNameDirConflictPreviewsMergeCount() {
        val srcDir = tmp.newFolder("sub-src").also { dir ->
            File(dir, "same.txt").writeText("s")
            File(dir, "new.txt").writeText("n")
            File(dir, "nested").mkdirs().let { File(dir, "nested/x.txt").writeText("x") }
        }
        val target = tmp.newFolder("target2")
        val dstDir = File(target, "sub-src").also { dir ->
            dir.mkdirs()
            File(dir, "same.txt").writeText("d")
            File(dir, "nested").mkdirs().let { File(dir, "nested/x.txt").writeText("x") }
        }
        val p = plan(listOf(srcDir), target, roots(tmp.newFolder("sb6"), tmp.newFolder("ws6")))
        val conflict = p.issues.filterIsInstance<MoveIssue>().single { it.kind == IssueKind.CONFLICT_DIR_MERGE }
        // 预演合并冲突条目：same.txt + nested/ + nested/x.txt = 3
        assertEquals(3, conflict.conflictCount)
        assertTrue(dstDir.exists())
    }

    @Test
    fun fileVsDirSameNameIsFileConflict() {
        val srcFile = tmp.newFile("clash").also { it.writeText("f") }
        val target = tmp.newFolder("target3")
        File(target, "clash").mkdirs()
        val p = plan(listOf(srcFile), target, roots(tmp.newFolder("sb7"), tmp.newFolder("ws7")))
        assertNotNull(p.issues.singleOrNull { it.kind == IssueKind.CONFLICT_FILE })
    }

    @Test
    fun overwriteDecisionProducesReplaceOrMergeTasks() {
        val srcFile = tmp.newFile("b.txt").also { it.writeText("b") }
        val srcDir = tmp.newFolder("bdir")
        val target = tmp.newFolder("target4")
        File(target, "b.txt").writeText("old")
        File(target, "bdir").mkdirs().also { File(target, "bdir/old.txt").writeText("keep") }
        val decisions = mapOf(
            srcFile.absolutePath to ConflictMode.OVERWRITE,
            srcDir.absolutePath to ConflictMode.OVERWRITE,
        )
        val p = plan(listOf(srcFile, srcDir), target, roots(tmp.newFolder("sb8"), tmp.newFolder("ws8")))
        // 未决策时仍是冲突
        assertEquals(2, p.issues.size)
        // 决策后：文件 → OVERWRITE 替换；目录 → MERGE_DIR 合并
        //（planner 产出 canonical 源路径，Windows 上与原始 File 不同形，按 canonical 比较）
        val p2 = MovePlanner.planMove(
            MoveRequest(
                sources = listOf(srcFile, srcDir),
                targetDir = target,
                conflictDecisions = decisions,
                roots = roots(tmp.newFolder("sb9"), tmp.newFolder("ws9")),
            ),
        )
        assertTrue(p2.issues.isEmpty())
        assertEquals(2, p2.items.size)
        val fileTask = p2.items.single { it.source.canonicalFile == srcFile.canonicalFile }
        assertEquals(MoveExisting.OVERWRITE, fileTask.existing)
        val dirTask = p2.items.single { it.source.canonicalFile == srcDir.canonicalFile }
        assertEquals(MoveExisting.MERGE_DIR, dirTask.existing)
    }

    @Test
    fun skipDecisionDropsSource() {
        val src = tmp.newFile("c.txt")
        val target = tmp.newFolder("target5")
        File(target, "c.txt").writeText("exists")
        val p = MovePlanner.planMove(
            MoveRequest(
                sources = listOf(src),
                targetDir = target,
                conflictDecisions = mapOf(src.absolutePath to ConflictMode.SKIP),
                roots = roots(tmp.newFolder("sb10"), tmp.newFolder("ws10")),
            ),
        )
        assertTrue(p.issues.isEmpty())
        assertTrue(p.items.isEmpty())
        assertEquals(listOf(src.canonicalFile), p.skippedSources)
    }

    @Test
    fun renameDecisionUsesCounterNaming() {
        val src = tmp.newFile("d.txt").also { it.writeText("d") }
        val target = tmp.newFolder("target6")
        File(target, "d.txt").writeText("1")
        File(target, "d-1.txt").writeText("2")
        val p = MovePlanner.planMove(
            MoveRequest(
                sources = listOf(src),
                targetDir = target,
                conflictDecisions = mapOf(src.absolutePath to ConflictMode.RENAME),
                roots = roots(tmp.newFolder("sb11"), tmp.newFolder("ws11")),
            ),
        )
        assertTrue(p.issues.isEmpty())
        assertEquals(1, p.items.size)
        assertEquals("d-2.txt", p.items.single().dest.name)
    }

    @Test
    fun renameBatchDeduplicatesReservedDestinations() {
        // 复查修正回归：同批两个同名源（不同目录）都选 RENAME，落点去重为 a-1 与 a-2，
        // 而不是双方都拿到 a-1 后在执行期撞「目标已存在」
        val dirA = tmp.newFolder("ren-a")
        val dirB = tmp.newFolder("ren-b")
        val sA = File(dirA, "a.txt").apply { writeText("A") }
        val sB = File(dirB, "a.txt").apply { writeText("B") }
        val target = tmp.newFolder("target10")
        File(target, "a.txt").writeText("occupied")
        val p = MovePlanner.planMove(
            MoveRequest(
                sources = listOf(sA, sB),
                targetDir = target,
                conflictDecisions = mapOf(
                    sA.absolutePath to ConflictMode.RENAME,
                    sB.absolutePath to ConflictMode.RENAME,
                ),
                roots = roots(tmp.newFolder("sb15"), tmp.newFolder("ws15")),
            ),
        )
        assertTrue(p.issues.isEmpty())
        assertEquals(2, p.items.size)
        val names = p.items.map { it.dest.name }.sorted()
        assertEquals(listOf("a-1.txt", "a-2.txt"), names)
    }

    @Test
    fun globalPreferenceAppliesToAllConflicts() {
        val s1 = tmp.newFile("e1.txt")
        val s2 = tmp.newFile("e2.txt")
        val target = tmp.newFolder("target7")
        File(target, "e1.txt").writeText("x")
        File(target, "e2.txt").writeText("x")
        val p = MovePlanner.planMove(
            MoveRequest(
                sources = listOf(s1, s2),
                targetDir = target,
                conflictPreference = ConflictMode.OVERWRITE,
                roots = roots(tmp.newFolder("sb12"), tmp.newFolder("ws12")),
            ),
        )
        assertTrue(p.issues.isEmpty())
        assertEquals(2, p.items.size)
        assertTrue(p.items.all { it.existing == MoveExisting.OVERWRITE })
    }

    // ---------- 系统目录拒绝 ----------

    @Test
    fun systemDirSourceAndTargetAreForbidden() {
        val sandbox = tmp.newFolder("sandbox-sys")
        val workspace = tmp.newFolder("ws-sys")
        val r = roots(sandbox, workspace)
        val proc = File(sandbox, "proc")
        val normal = File(workspace, "n.txt")
        // 源为系统目录
        val p1 = plan(listOf(proc), workspace, r)
        assertEquals(1, p1.issues.count { it.kind == IssueKind.SYSTEM_DIR_FORBIDDEN })
        assertTrue(p1.items.isEmpty())
        // 目标为系统目录
        val p2 = plan(listOf(normal), File(sandbox, "tmp"), r)
        assertEquals(1, p2.issues.count { it.kind == IssueKind.SYSTEM_DIR_FORBIDDEN })
        assertTrue(p2.items.isEmpty())
    }

    // ---------- 跨层标注 ----------

    @Test
    fun layerRiskReportCoversSourceAndTarget() {
        val sandbox = tmp.newFolder("sandbox-risk")
        val workspace = tmp.newFolder("ws-risk")
        val node = tmp.newFolder("node-risk")
        val dsh = tmp.newFolder("dsh-risk")
        val r = roots(sandbox, workspace, node, dsh)
        val src = File(node, "bin/node")
        val p = MovePlanner.planMove(
            MoveRequest(sources = listOf(src), targetDir = dsh, roots = r, sandboxRunning = true),
        )
        assertEquals(1, p.layerRisks.size)
        val risk = p.layerRisks.single()
        assertEquals(Layer.NODE, risk.sourceLayer)
        assertEquals(Layer.DSH, risk.targetLayer)
        assertTrue(risk.sandboxRunning)
    }

    @Test
    fun duplicateSourcesAreDeduplicated() {
        val src = tmp.newFile("dup.txt")
        val target = tmp.newFolder("target8")
        val p = plan(listOf(src, File(src.absolutePath)), target, roots(tmp.newFolder("sb13"), tmp.newFolder("ws13")))
        assertEquals(1, p.items.size)
    }

    @Test
    fun missingSourceStillPlans() {
        // 源不存在：计划阶段不阻断（执行时按「源不存在」计入失败）
        val ghost = File(tmp.root, "ghost.txt")
        val target = tmp.newFolder("target9")
        val p = plan(listOf(ghost), target, roots(tmp.newFolder("sb14"), tmp.newFolder("ws14")))
        assertEquals(1, p.items.size)
        assertNull(p.issues.singleOrNull { it.kind == IssueKind.CONFLICT_FILE })
    }

    // ---------- §5.6 刷新集合（复查修正回归） ----------

    @Test
    fun refreshDirsCoverSourceParentsAndTarget() {
        // 复查修正场景：在 A 移动文件到 B，刷新集合必须同时包含 A（源父目录）与 B（目标目录）
        val home = tmp.newFolder("mv-home")
        val target = tmp.newFolder("mv-target")
        val src = File(home, "a.txt").apply { writeText("x") }
        val dirs = MovePlanner.computeMoveRefreshDirs(
            items = listOf(MoveTask(src, File(target, "a.txt"))),
            skippedSources = emptyList(),
        )
        assertTrue("源父目录必须进刷新集合", dirs.contains(home.canonicalFile.absolutePath))
        assertTrue("目标目录必须进刷新集合", dirs.contains(target.canonicalFile.absolutePath))
        assertEquals(2, dirs.size)
    }

    @Test
    fun refreshDirsIncludeSkippedSourceParents() {
        val home = tmp.newFolder("mv-home2")
        val target = tmp.newFolder("mv-target2")
        val dirs = MovePlanner.computeMoveRefreshDirs(
            items = emptyList(),
            skippedSources = listOf(File(home, "b.txt")),
        )
        assertTrue(dirs.contains(home.canonicalFile.absolutePath))
    }

    @Test
    fun refreshDirsAreDeduplicatedAndCanonical() {
        // 同一父目录的多个源 + 目标即该父目录：去重后仅一项
        val home = tmp.newFolder("mv-home3")
        val s1 = File(home, "a.txt")
        val s2 = File(home, "b.txt")
        val dirs = MovePlanner.computeMoveRefreshDirs(
            items = listOf(
                MoveTask(s1, File(home, "a.txt")),
                MoveTask(s2, File(home, "b.txt")),
            ),
            skippedSources = emptyList(),
        )
        assertEquals(setOf(home.canonicalFile.absolutePath), dirs)
    }

    // ---------- §5.4 阶段二跨层确认判定（复查第五轮回归） ----------

    private fun risk(source: Layer, target: Layer) = LayerRiskReport(
        source = File("s"), target = File("t"), sourceLayer = source, targetLayer = target,
        sandboxRunning = false,
    )

    @Test
    fun workspaceToWorkspaceNeedsNoConfirm() {
        assertFalse(
            MovePlanner.needsCrossLayerConfirm(
                listOf(risk(Layer.WORKSPACE, Layer.WORKSPACE)), sourcePrecheckFired = false,
            ),
        )
    }

    @Test
    fun workspaceToOtherLayersNeedsConfirm() {
        // 复查静默场景 1/2：workspace → base / node / dsh / .dsh 目标，全部强确认
        assertTrue(MovePlanner.needsCrossLayerConfirm(listOf(risk(Layer.WORKSPACE, Layer.BASE)), false))
        assertTrue(MovePlanner.needsCrossLayerConfirm(listOf(risk(Layer.WORKSPACE, Layer.NODE)), false))
        assertTrue(MovePlanner.needsCrossLayerConfirm(listOf(risk(Layer.WORKSPACE, Layer.DSH)), false))
        assertTrue(MovePlanner.needsCrossLayerConfirm(listOf(risk(Layer.WORKSPACE, Layer.DSH_DATA)), false))
    }

    @Test
    fun baseSourceToAnyLayerNeedsConfirm() {
        // 复查静默场景 3：base 源（riskLevelOfLayer 返回 null）跨层一律强确认
        assertTrue(MovePlanner.needsCrossLayerConfirm(listOf(risk(Layer.BASE, Layer.WORKSPACE)), false))
        assertTrue(MovePlanner.needsCrossLayerConfirm(listOf(risk(Layer.BASE, Layer.BASE)), false))
        assertTrue(MovePlanner.needsCrossLayerConfirm(listOf(risk(Layer.BASE, Layer.NODE)), false))
    }

    @Test
    fun sourcePrecheckDedupesOnlyWhenTargetIsBenign() {
        // 源侧预检已确认（node 源）：目标在 workspace/base 不重复弹窗；目标入 node/dsh/.dsh 仍要确认（目标侧）
        assertFalse(MovePlanner.needsCrossLayerConfirm(listOf(risk(Layer.NODE, Layer.WORKSPACE)), true))
        assertFalse(MovePlanner.needsCrossLayerConfirm(listOf(risk(Layer.NODE, Layer.BASE)), true))
        assertTrue(MovePlanner.needsCrossLayerConfirm(listOf(risk(Layer.NODE, Layer.NODE)), true))
        assertTrue(MovePlanner.needsCrossLayerConfirm(listOf(risk(Layer.DSH_DATA, Layer.DSH_DATA)), true))
    }

    @Test
    fun mixedLayerBatchStillNeedsConfirm() {
        // 复查第八轮修正回归：/usr 下 local(NODE)+bin(BASE) 同目录混选移向 workspace——
        // 去重仅对「全部源已涉险」生效，混合批中 BASE 项必须有矩阵确认（不可静默移出 rootfs）
        val mixed = listOf(risk(Layer.NODE, Layer.WORKSPACE), risk(Layer.BASE, Layer.WORKSPACE))
        assertTrue(MovePlanner.needsCrossLayerConfirm(mixed, sourcePrecheckFired = true))
        // NODE + workspace 普通项混批同理（批内有非涉险源即不整体去重）
        val mixedWs = listOf(risk(Layer.NODE, Layer.WORKSPACE), risk(Layer.WORKSPACE, Layer.WORKSPACE))
        assertTrue(MovePlanner.needsCrossLayerConfirm(mixedWs, sourcePrecheckFired = true))
    }

    @Test
    fun emptyRisksNeedNoConfirm() {
        assertFalse(MovePlanner.needsCrossLayerConfirm(emptyList(), false))
    }
}

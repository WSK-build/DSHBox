package com.dshbox.app.ui.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `clearUploadAccept` 脚本的单测（1.3.1 复查 M30）。
 *
 * ## 被测对象
 *
 * [CLEAR_UPLOAD_ACCEPT_JS] —— 沙箱上传结果回填后，清掉网页端隐藏
 * `<input type="file">` 上残留的 `accept` 哨兵值。
 *
 * ## 为什么需要它
 *
 * 哨兵值（[SANDBOX_UPLOAD_SENTINEL]）是原生用来分辨「这次文件选择来自沙箱选择器
 * 还是系统选择器」的唯一信号。它本应由网页侧插件在 change/cancel/捕获 click
 * 三条路径复位，但**沙箱路径是例外**：选择器是 app 自绘的 Compose 对话框，
 * 不经过系统文件选择器，`<input>` 的 value 从未改变 → 不触发 change。
 * 于是哨兵会一直留在元素上，污染之后走 dsh 自有入口的上传。
 * 这里在结果回填的确切时刻主动清理，与插件侧守卫形成双保险。
 *
 * ## 为什么是「断言字符串」而不是「跑 JS」
 *
 * 单测跑在纯 JVM 上（本模块无 Robolectric），起不了 WebView。
 * 但这段脚本的失效模式恰恰都是**结构性**的 —— 少一个分号、选错 API、
 * 漏掉 try/catch —— 都由字符串断言即可锁住。
 * 真实 DOM 行为已在 `tools/webview_upload_accept_test.html` 里
 * 用浏览器夹具实测（含负向对照）。
 */
class ClearUploadAcceptScriptTest {

    private val js = CLEAR_UPLOAD_ACCEPT_JS

    /**
     * **核心**：必须用 `querySelectorAll` 全量遍历，而不是 `querySelector` 只取第一个。
     *
     * 多 input 的页面上（dsh 的 composer 行 + 其它扩展各自可能插入），
     * 只清第一个会留下其余元素的哨兵 —— 而它们同样会把系统选择器
     * 误判成沙箱上传。这是本脚本与早期注释描述不符之处（注释已订正）。
     */
    @Test
    fun clearsAllFileInputsNotJustTheFirst() {
        assertTrue("必须用 querySelectorAll 全量选取", js.contains("querySelectorAll"))
        assertFalse("不得退回只取第一个元素的 querySelector", js.contains("querySelector('"))
    }

    /** 选择器必须限定 file 类型，不能扫掉页面上所有 input。 */
    @Test
    fun selectorTargetsFileInputsOnly() {
        assertTrue(
            "选择器应为 input[type=\"file\"]",
            js.contains("input[type=\"file\"]"),
        )
        assertFalse("不得使用无类型限定的 input 选择器", js.contains("querySelectorAll('input')"))
    }

    /**
     * 必须用 `removeAttribute` 而非赋空串。
     *
     * `el.accept = ''` 之后 `hasAttribute('accept')` 仍为真；部分实现
     * 会据此走「存在 accept 即按类型过滤」的分支，等于没清干净。
     */
    @Test
    fun removesAttributeRatherThanAssigningEmptyString() {
        assertTrue("应用 removeAttribute 真正摘掉属性", js.contains("removeAttribute('accept')"))
        assertFalse("不得用赋值空串代替移除", js.contains("accept = ''") || js.contains("accept=\"\""))
    }

    /**
     * 必须有 `try/catch` 兜底。
     *
     * 这段脚本在「结果回填」时刻执行，页面可能正开始跳转或销毁 ——
     * 未捕获的异常会让 `evaluateJavascript` 报错并打断调用方后续逻辑。
     */
    @Test
    fun isWrappedInTryCatch() {
        assertTrue("缺少 try", js.contains("try{"))
        assertTrue("缺少 catch", js.contains("catch(e)"))
    }

    /** 必须是自执行函数表达式，避免在页面全局作用域留下变量。 */
    @Test
    fun isSelfContainedIife() {
        assertTrue("应以 IIFE 形式执行", js.startsWith("(function(){"))
        assertTrue("应以 })() 收尾", js.endsWith("})()"))
        // 变量必须用 var 声明，否则在严格模式页面下会抛 ReferenceError
        assertTrue("循环变量应显式声明", js.contains("var els=") && js.contains("var i="))
    }

    /**
     * 全量遍历的语义要求：循环上界取自 `els.length`，且逐个 `removeAttribute`。
     * 若误写成 `els[0]` 或漏掉循环体，等于只清了第一个。
     */
    @Test
    fun iteratesOverEveryMatchedElement() {
        assertTrue("循环上界应取 els.length", js.contains("i<els.length"))
        assertTrue("循环体内应逐个移除属性", js.contains("els[i].removeAttribute('accept')"))
    }
}

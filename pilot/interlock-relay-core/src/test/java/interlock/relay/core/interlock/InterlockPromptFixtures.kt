package interlock.relay.core.interlock

/**
 * 确认框测试里「整份参数完全相同」这一档的固定身份值。
 *
 * `argsIdentity` 是判据，`targetLabel` 与 `paramDetail` 只是给人看的呈现文字。用例默认让身份
 * 恒等于这一个常量，凡是要断言「这是两件事」的都得显式交出不同的身份 —— 否则测的就成了
 * 测试构造器本身，判据放宽也照样全绿。
 */
internal const val SAME_ARGS = "same-args"

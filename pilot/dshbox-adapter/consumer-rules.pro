# dshbox-adapter 的消费者混淆规则，随 AAR 传递给宿主应用。
# 本模块两个清单组件（PilotActivity、PilotApprovalReceiver）由 AGP 按清单引用自动保留，
# 其余类型没有反射或 binder 直连的调用点；核心模块的 keep 规则经 api 依赖自行传递。

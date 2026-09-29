# Shizuku instantiates the user service reflectively by class name and hands the
# instance back as IBinder; the class and constructor must survive minification.
-keep class interlock.relay.core.exec.shizuku.RelayShellService { *; }

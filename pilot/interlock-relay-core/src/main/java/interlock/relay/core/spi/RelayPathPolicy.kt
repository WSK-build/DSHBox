package interlock.relay.core.spi

/**
 * 路径与命名策略。core 默认只给通用默认值；宿主传自己的既有取值即可让沙盒侧看到的一切
 * 与宿主既有部署完全一致。子目录名（run/inbox/outbox/control-inbox/control-outbox/
 * out/artifacts/uploads）是固定词，不随策略变。
 */
interface RelayPathPolicy {

    /** 沙盒侧挂载点（PRoot `--bind` 的 guest 路径）。 */
    val guestEntry: String

    /** 宿主目录名：filesDir 下的共享树目录名；私有目录在其后加 -state/-stage/-audit 后缀。 */
    val hostDirName: String

    /** 沙盒侧命令名：物化到 `<guestEntry>/bin/` 下的无扩展名脚本名。 */
    val cliName: String

    /** 产物文件名前缀（入口脚本在缺省 `--out` 时使用；须与命令名保持同源）。 */
    val artifactPrefix: String

    /** 打包在 assets 里的入口脚本路径。 */
    val assetClient: String

    /**
     * 写进系统媒体库的相册目录名：`Pictures/<目录名>/`、`Movies/<目录名>/` 与
     * `Music/<目录名>/` 里的 `<目录名>`。写入（[interlock.relay.core.exec.direct.DirectBackend]
     * 的 media.write 落点）与占用对账（[interlock.relay.core.storage.MediaLibraryUsage] 的
     * 查询前缀）都从这一处取，两边才永远指向同三个目录。
     */
    val mediaAlbumDir: String

    /** core 默认值：通用默认值。宿主传入自己的取值即可，让沙盒侧与媒体库看到的目录名与宿主部署一致。 */
    companion object {
        const val DEFAULT_GUEST_ENTRY = "/opt/interlock-relay"
        const val DEFAULT_HOST_DIR = "relay"
        const val DEFAULT_CLI_NAME = "relay"
        const val DEFAULT_ARTIFACT_PREFIX = "relay-"
        const val DEFAULT_ASSET_CLIENT = "relay/relay-client.cjs"
        const val DEFAULT_MEDIA_ALBUM_DIR = "Relay"
    }

    /** 缺省实现：core 内建默认值。 */
    object BuiltIn : RelayPathPolicy {
        override val guestEntry: String = DEFAULT_GUEST_ENTRY
        override val hostDirName: String = DEFAULT_HOST_DIR
        override val cliName: String = DEFAULT_CLI_NAME
        override val artifactPrefix: String = DEFAULT_ARTIFACT_PREFIX
        override val assetClient: String = DEFAULT_ASSET_CLIENT
        override val mediaAlbumDir: String = DEFAULT_MEDIA_ALBUM_DIR
    }
}

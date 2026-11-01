package developer.access

import com.dshbox.app.common.UiText
import developer.access.core.PortStatus
import developer.access.core.ServerSpec
import developer.access.core.ServerTransport

/** 面板状态快照。 */
data class DeveloperAccessUiState(
    val adbEnabled: Boolean = false,
    val adbSpec: ServerSpec = ServerSpec(),
    val rootEnabled: Boolean = false,
    val rootSpec: ServerSpec = ServerSpec(
        transport = ServerTransport.UNIX_SOCKET,
        port = developer.access.core.ElevationPaths.DEFAULT_ROOT_SERVER_PORT,
    ),
    val rootAccessCode: String = "",
    /** 访问码因连续失败被拒绝：需重新生成才能恢复。 */
    val rootAuthLocked: Boolean = false,
    val suAvailable: Boolean = false,
    val keys: List<developer.access.adb.KeyStore.KeyInfo> = emptyList(),
    val hasPairedDevices: Boolean = false,
    val pairingOpen: Boolean = false,
    val pairingStep: developer.access.ui.PairingStep = developer.access.ui.PairingStep.INTRO,
    val pairingCode: String = "",
    val pairingBusy: Boolean = false,
    val pairingError: UiText? = null,
    val pairingHost: String = "",
    val pairingPort: Int = 0,
    val pairingAddress: String = "",
    /**
     * 手填的**配对**地址（`ip:端口`）。
     *
     * mDNS 自动发现不是每次都能拿到（无线调试刚开、路由器拦组播等）；发现失败时
     * 用户可从「使用配对码配对」弹窗里读到 IP 与端口，手填后仍能走完配对。
     */
    val pairingPairDraft: String = "",

    /**
     * 当前配对地址是否来自自动发现（而非用户手填）。
     *
     * 只有自动值才允许被后续发现覆盖：手机侧重开配对弹窗会换端口，
     * 若把"已填"一律当成用户输入，地址就会永远停在旧端口上。
     */
    val pairingAddressAuto: Boolean = false,

    /** 正在自动检测地址（只影响"自动检测地址"按钮的文案，不拦输入）。 */
    val pairingDiscovering: Boolean = false,
    /** 传统授权方式的连接地址（`ip:端口`），`adb connect` 用。 */
    val directConnectDraft: String = "",
    /** 直接连接的就地反馈（连接中/已连接/未授权/不可达/格式错）。 */
    val directConnectStatus: UiText? = null,
    val directConnectStatusIsError: Boolean = false,
    val directConnectBusy: Boolean = false,
    /**
     * 配对成功但自动连接失败：需要用户从无线调试界面抄一次连接地址。
     *
     * 配对端口与连接端口是两个不同的 mDNS 服务，自动发现失败时不能拿配对端口凑数，
     * 故向导在此态展开手填入口。
     */
    val pairingNeedsConnectTarget: Boolean = false,
    /** 手填的连接地址（`ip:port`）。 */
    val pairingConnectDraft: String = "",
    /** 已建立的连接地址（配对成功后展示并可复制）。 */
    val pairingConnectAddress: String = "",
    /** 编辑中的 ADB 端口（点 [应用] 才落盘）。 */
    val adbDraftPort: Int = ServerSpec().port,
    /** 编辑中的自定义 spec 原文。 */
    val adbDraftCustom: String = "",
    /** ADB 端口的可用性探测结果。 */
    val adbPortStatus: PortStatus = PortStatus.UNKNOWN,
    /**
     * 已落盘的 ADB 连接方式（即 `server.conf` 的内容，DSH 也读这一份）。
     *
     * 与 [adbSpec]（编辑中的草稿）分开保存，才能判断"界面上选的和真正生效的是否一致"。
     */
    val adbPersistedSpec: ServerSpec = ServerSpec(),
    /** 编辑中的 Root 端口。 */
    val rootDraftPort: Int = developer.access.core.ElevationPaths.DEFAULT_ROOT_SERVER_PORT,
    /** Root 端口的可用性探测结果。 */
    val rootPortStatus: PortStatus = PortStatus.UNKNOWN,
    /** 已落盘的 Root 连接方式。 */
    val rootPersistedSpec: ServerSpec = ServerSpec(
        transport = ServerTransport.UNIX_SOCKET,
        port = developer.access.core.ElevationPaths.DEFAULT_ROOT_SERVER_PORT,
    ),
    val sandboxRunning: Boolean = false,
    val devOptionsEnabled: Boolean = false,
    val pairedDevices: List<String> = emptyList(),
    /** 当前在线设备（`adb devices` 的结果）。 */
    val connectedDevices: List<String> = emptyList(),
    /** 是否已查询过一次在线设备（区分"没查"与"查了但为空"）。 */
    val devicesLoaded: Boolean = false,
    /** 正在查询/断开设备。 */
    val devicesBusy: Boolean = false,
    val busy: Boolean = false,
    val hint: UiText? = null,
    val hintIsError: Boolean = false,
    /**
     * 提示归属哪一张卡。
     *
     * 面板很长，把提示统一堆在页尾会让"点了就有反应"的那一下看不见——按动作来源
     * 落在对应卡片下方，用户视线不必跳走。
     */
    val hintFromRoot: Boolean = false,
    /** 关闭 ADB 通道后需弹窗告知"手机侧授权仍在"。 */
    val showAdbDisableNotice: Boolean = false,
)

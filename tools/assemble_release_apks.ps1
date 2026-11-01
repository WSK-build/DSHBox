# assemble_release_apks.ps1
# 双 APK 发布收口（A4，轻量）：把 Gradle 已产出的两个 release 制品整理为
# 清晰的 gecko / systemWebView 两类交付物，归档到仓库根的 apk/ 目录。
#
# 设计约束（红线，本脚本不触碰）：
#   - 不改任何业务代码 / DSH 渲染器 / 插件资产；
#   - 不动签名配置；本脚本只是“复制归档”，不参与打包、不重新签名；
#   - 不改构建语义：Gradle 仍按 flavor 把产物落在
#     app/build/outputs/apk/<flavor>/release/app-<flavor>-release.apk，
#     这里只用 gradle 的既定输出，不再暴露 debug/release 中间层级。
#
# 用法：
#   1) 先按既定命令构建（见 docs/双APK产物说明.md 的“构建命令”）：
#        gradlew :app:assembleGeckoRelease :app:assembleSystemWebViewRelease
#   2) 再运行本脚本归档：
#        pwsh source/tools/assemble_release_apks.ps1
#   或一步到位（脚本内部自行调用 gradlew）：
#        pwsh source/tools/assemble_release_apks.ps1 -Build
#
# 归档结果（仓库根 apk/，版本号取自 app/build.gradle.kts 的 versionName）：
#   apk/dshbox-v<version>-system-release.apk
#   apk/dshbox-v<version>-gecko-release.apk
#
# 版本号由脚本从 Gradle 配置现取，不再手写；发布时改 versionName 一处即可。

param(
    # 为 $true 时先调用 gradlew 执行两个 assemble*Release，再归档。
    [switch]$Build,

    # 可选输出目录；默认是当前源码仓库根目录下的 apk/。
    [string]$DistDir
)

$ErrorActionPreference = 'Stop'

$RepoRoot   = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path           # .../source
$BuildOut   = Join-Path $RepoRoot 'app\build\outputs\apk'
if ([string]::IsNullOrWhiteSpace($DistDir)) {
    $DistDir = Join-Path $RepoRoot 'apk'
} else {
    $DistDir = [System.IO.Path]::GetFullPath($DistDir)
}

# Gradle 侧既定输出名（内含中间层 debug/release，不对外暴露）。
$Artifacts = @{
    'gecko'          = 'app-gecko-release.apk'
    'systemWebView'  = 'app-systemWebView-release.apk'
}

# 版本号来源：app/build.gradle.kts 的 versionName。
$GradleAppFile = Join-Path $RepoRoot 'app\build.gradle.kts'
$versionMatch = Select-String -Path $GradleAppFile -Pattern 'versionName\s*=\s*"([^"]+)"' |
    Select-Object -First 1
if (-not $versionMatch) {
    Write-Error "无法从 $GradleAppFile 解析 versionName"
}
$Version = $versionMatch.Matches[0].Groups[1].Value

# 对外交付名：与历史命名一致，额外带 flavor 区分字段。
$Deliverables = @{
    'gecko'         = "dshbox-v$Version-gecko-release.apk"
    'systemWebView' = "dshbox-v$Version-system-release.apk"
}

if ($Build) {
    Write-Host '==> 调用 gradlew 构建两个 release 变体'
    $env:GRADLE_USER_HOME = $env:GRADLE_USER_HOME
    & (Join-Path $RepoRoot 'gradlew.bat') -p $RepoRoot `
        :app:assembleGeckoRelease :app:assembleSystemWebViewRelease
    if ($LASTEXITCODE -ne 0) {
        Write-Error "gradlew assemble*Release 失败（exit=$LASTEXITCODE）"
    }
}

New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

Write-Host "==> 版本：v$Version"

foreach ($flavor in $Artifacts.Keys) {
    $src = Join-Path $BuildOut "$flavor\release\$($Artifacts[$flavor])"
    $dst = Join-Path $DistDir $Deliverables[$flavor]
    if (-not (Test-Path $src)) {
        Write-Error "未找到既定构建产物：$src`n请先运行 gradlew :app:assemble${flavor}Release（或带 -Build 参数）。"
    }
    Copy-Item -Force $src $dst
    $sizeMB = [math]::Round((Get-Item $dst).Length / 1MB, 2)
    Write-Host "==> 归档 $($Artifacts[$flavor]) -> $($Deliverables[$flavor]) ($sizeMB MB)"
}

# 交付目录只保留对外命名；清理历史运行可能留下的 Gradle 中间名副本。
foreach ($artifact in $Artifacts.Values) {
    $intermediateCopy = Join-Path $DistDir $artifact
    if (Test-Path -LiteralPath $intermediateCopy) {
        Remove-Item -LiteralPath $intermediateCopy -Force
        Write-Host "==> 清理中间名副本 $artifact"
    }
}

Write-Host '==> 交付物已整理到 apk/（gecko / systemWebView 两类）'
Get-ChildItem $DistDir -File | Format-Table Name, @{N='MB';E={[math]::Round($_.Length/1MB,2)}} | Out-String | Write-Host

# 归档后立即执行发布契约门禁：资产缺失、权限缺失或产物与当前源码不一致时
# 直接失败，禁止把过期/残缺包当成交付物。
$verify = Join-Path $PSScriptRoot 'verify_release_apk_contract.ps1'
if (Test-Path $verify) {
    Write-Host '==> 执行发布契约门禁（verify_release_apk_contract）'
    try {
        & $verify `
            -SystemApk (Join-Path $DistDir $Deliverables['systemWebView']) `
            -GeckoApk (Join-Path $DistDir $Deliverables['gecko'])
    } catch {
        Write-Host "发布契约门禁失败：$($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
    Write-Host '==> 发布契约门禁通过'
} else {
    Write-Host '发布契约门禁脚本缺失，归档流程中止' -ForegroundColor Red
    exit 1
}

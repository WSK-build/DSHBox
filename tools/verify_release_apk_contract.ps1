<#
.SYNOPSIS
  Verify the release APKs contain the APP-owned DSH page compatibility files.

.DESCRIPTION
  Release gate. Opens both APKs, extracts the required extension assets, then
  verifies the Gecko manifest and the shared-script hash.

.EXAMPLE
  .\source\tools\verify_release_apk_contract.ps1 `
    -SystemApk .\source\app\build\outputs\apk\systemWebView\release\app-systemWebView-release.apk `
    -GeckoApk .\source\app\build\outputs\apk\gecko\release\app-gecko-release.apk
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SystemApk,

    [Parameter(Mandatory = $true)]
    [string]$GeckoApk,

    [string]$ExtractDir,

    [string]$ExpectedPageCompat
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$requiredPermissions = @(
    'geckoViewAddons',
    'nativeMessaging',
    'nativeMessagingFromContent'
)
$systemEntries = @(
    'assets/dsh-renderer/dsh-page-compat.js',
    'assets/dsh-compat/standard-bootstrap.js',
    'assets/dsh-compat/worker-capability-probe.js'
)
$geckoEntries = @(
    'assets/dsh-status-bridge/manifest.json',
    'assets/dsh-status-bridge/background.js',
    'assets/dsh-status-bridge/content.js',
    'assets/dsh-status-bridge/page-compat-config.js',
    'assets/dsh-status-bridge/dsh-page-compat.js'
)

function Resolve-ReleaseApk {
    param(
        [string]$Path,
        [string]$Name
    )

    $resolved = Resolve-Path -LiteralPath $Path
    $file = Get-Item -LiteralPath $resolved.ProviderPath
    if (!$file.Name.EndsWith('.apk', [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Name is not an APK: $($file.FullName)"
    }
    if ($file.Name -notmatch '(?i)release') {
        throw "$Name does not look like a release APK: $($file.Name)"
    }
    return $file
}

function Resolve-AndroidBuildTool {
    param([string]$Name)

    $sdkCandidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    foreach ($sdkRoot in $sdkCandidates) {
        $buildToolsRoot = Join-Path $sdkRoot 'build-tools'
        if (!(Test-Path -LiteralPath $buildToolsRoot)) {
            continue
        }
        $tool = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
            Sort-Object Name -Descending |
            ForEach-Object {
                $candidate = Join-Path $_.FullName $Name
                if (Test-Path -LiteralPath $candidate) {
                    Get-Item -LiteralPath $candidate
                }
            } |
            Select-Object -First 1
        if ($null -ne $tool) {
            return $tool.FullName
        }
    }
    throw "Android build tool not found: $Name"
}

function Get-ReleaseApkIdentity {
    param(
        [System.IO.FileInfo]$Apk,
        [string]$Aapt
    )

    $badging = & $Aapt dump badging $Apk.FullName
    if ($LASTEXITCODE -ne 0) {
        throw "aapt failed to inspect $($Apk.Name)"
    }
    $packageLine = $badging | Where-Object { $_ -like 'package:*' } |
        Select-Object -First 1
    if ($packageLine -notmatch "name='([^']+)' versionCode='([^']+)' versionName='([^']+)'") {
        throw "Cannot parse package identity from $($Apk.Name)"
    }
    return [pscustomobject]@{
        PackageName = $Matches[1]
        VersionCode = $Matches[2]
        VersionName = $Matches[3]
    }
}

function Get-ReleaseApkSigner {
    param(
        [System.IO.FileInfo]$Apk,
        [string]$ApkSigner
    )

    $verification = & $ApkSigner verify --print-certs $Apk.FullName
    if ($LASTEXITCODE -ne 0) {
        throw "apksigner failed to verify $($Apk.Name)"
    }
    $line = $verification |
        Where-Object { $_ -match 'certificate SHA-256 digest:\s*([0-9a-fA-F]+)' } |
        Select-Object -First 1
    if (-not $line) {
        throw "Cannot parse signer certificate from $($Apk.Name)"
    }
    [void]($line -match 'certificate SHA-256 digest:\s*([0-9a-fA-F]+)')
    return $Matches[1].ToUpperInvariant()
}

function Extract-RequiredEntries {
    param(
        [System.IO.FileInfo]$Apk,
        [string[]]$Entries,
        [string]$Destination
    )

    [System.IO.Directory]::CreateDirectory($Destination) | Out-Null
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Apk.FullName)
    try {
        foreach ($name in $Entries) {
            $entry = $zip.GetEntry($name)
            if ($null -eq $entry) {
                throw "$($Apk.Name) is missing $name"
            }
            if ($entry.Length -le 0) {
                throw "$($Apk.Name) contains an empty $name"
            }
            $relative = $name.Replace('/', [System.IO.Path]::DirectorySeparatorChar)
            $target = Join-Path $Destination $relative
            [System.IO.Directory]::CreateDirectory(
                [System.IO.Path]::GetDirectoryName($target)
            ) | Out-Null
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile(
                $entry,
                $target,
                $true
            )
        }
    } finally {
        $zip.Dispose()
    }
}

$systemFile = Resolve-ReleaseApk -Path $SystemApk -Name 'SystemApk'
$geckoFile = Resolve-ReleaseApk -Path $GeckoApk -Name 'GeckoApk'
if ([string]::IsNullOrWhiteSpace($ExpectedPageCompat)) {
    $ExpectedPageCompat = Join-Path $PSScriptRoot (
        '../dsh-renderer/api/src/main/assets/dsh-renderer/dsh-page-compat.js'
    )
}
$expectedCompatFile = Get-Item -LiteralPath (Resolve-Path -LiteralPath $ExpectedPageCompat)
$expectedHash = (
    Get-FileHash -Algorithm SHA256 -LiteralPath $expectedCompatFile.FullName
).Hash
$expectedBootstrapHash = (
    Get-FileHash -Algorithm SHA256 -LiteralPath (
        Join-Path $PSScriptRoot '../dsh-renderer/system/src/main/assets/dsh-compat/standard-bootstrap.js'
    )
).Hash
$expectedWorkerProbeHash = (
    Get-FileHash -Algorithm SHA256 -LiteralPath (
        Join-Path $PSScriptRoot '../dsh-renderer/system/src/main/assets/dsh-compat/worker-capability-probe.js'
    )
).Hash
if ([string]::IsNullOrWhiteSpace($ExtractDir)) {
    $ExtractDir = Join-Path (
        [System.IO.Path]::GetTempPath()
    ) ("dshbox-apk-contract-" + [Guid]::NewGuid().ToString('N'))
}
$systemDir = Join-Path $ExtractDir 'system'
$geckoDir = Join-Path $ExtractDir 'gecko'

Extract-RequiredEntries -Apk $systemFile -Entries $systemEntries -Destination $systemDir
Extract-RequiredEntries -Apk $geckoFile -Entries $geckoEntries -Destination $geckoDir

$systemCompat = Join-Path $systemDir 'assets/dsh-renderer/dsh-page-compat.js'
$geckoCompat = Join-Path $geckoDir 'assets/dsh-status-bridge/dsh-page-compat.js'
$systemHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $systemCompat).Hash
$systemBootstrapHash = (
    Get-FileHash -Algorithm SHA256 -LiteralPath (
        Join-Path $systemDir 'assets/dsh-compat/standard-bootstrap.js'
    )
).Hash
$systemWorkerProbeHash = (
    Get-FileHash -Algorithm SHA256 -LiteralPath (
        Join-Path $systemDir 'assets/dsh-compat/worker-capability-probe.js'
    )
).Hash
$geckoHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $geckoCompat).Hash
if ($systemHash -ne $geckoHash) {
    throw "Shared page compatibility scripts differ: system=$systemHash gecko=$geckoHash"
}
if ($systemHash -ne $expectedHash) {
    throw "Packaged page compatibility is stale: packaged=$systemHash source=$expectedHash"
}
if ($systemBootstrapHash -ne $expectedBootstrapHash) {
    throw "Packaged System bootstrap is stale: packaged=$systemBootstrapHash source=$expectedBootstrapHash"
}
if ($systemWorkerProbeHash -ne $expectedWorkerProbeHash) {
    throw "Packaged Worker probe is stale: packaged=$systemWorkerProbeHash source=$expectedWorkerProbeHash"
}

$manifestPath = Join-Path $geckoDir 'assets/dsh-status-bridge/manifest.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$permissions = @($manifest.permissions)
foreach ($required in $requiredPermissions) {
    if ($permissions -notcontains $required) {
        throw "Gecko extension manifest is missing permission: $required"
    }
}

$pageScripts = @(
    $manifest.content_scripts |
        Where-Object {
            $world = $_.PSObject.Properties['world']
            $world -and $world.Value -eq 'MAIN' -and
            @($_.js) -contains 'dsh-page-compat.js'
        }
)
if ($pageScripts.Count -ne 1) {
    throw 'Gecko extension must contain exactly one MAIN-world page compatibility script'
}
$pageScript = $pageScripts[0]
if ($pageScript.run_at -ne 'document_start') {
    throw "Page compatibility run_at is $($pageScript.run_at), expected document_start"
}
if ($pageScript.all_frames -ne $false) {
    throw 'Page compatibility must be top-frame only (all_frames=false)'
}
$pageScriptFiles = @($pageScript.js)
if ($pageScriptFiles.IndexOf('page-compat-config.js') -ge
    $pageScriptFiles.IndexOf('dsh-page-compat.js')) {
    throw 'page-compat-config.js must execute before dsh-page-compat.js'
}

$gradleAppFile = Join-Path $PSScriptRoot '../app/build.gradle.kts'
$versionNameMatch = Select-String -LiteralPath $gradleAppFile `
    -Pattern 'versionName\s*=\s*"([^"]+)"' |
    Select-Object -First 1
$versionCodeMatch = Select-String -LiteralPath $gradleAppFile `
    -Pattern 'versionCode\s*=\s*(\d+)' |
    Select-Object -First 1
if (!$versionNameMatch -or !$versionCodeMatch) {
    throw 'Cannot parse versionCode/versionName from app/build.gradle.kts'
}
$expectedVersionName = $versionNameMatch.Matches[0].Groups[1].Value
$expectedVersionCode = $versionCodeMatch.Matches[0].Groups[1].Value

$aaptName = if ($IsWindows) { 'aapt.exe' } else { 'aapt' }
$apksignerName = if ($IsWindows) { 'apksigner.bat' } else { 'apksigner' }
$aapt = Resolve-AndroidBuildTool -Name $aaptName
$apksigner = Resolve-AndroidBuildTool -Name $apksignerName
$systemIdentity = Get-ReleaseApkIdentity -Apk $systemFile -Aapt $aapt
$geckoIdentity = Get-ReleaseApkIdentity -Apk $geckoFile -Aapt $aapt
foreach ($identity in @($systemIdentity, $geckoIdentity)) {
    if ($identity.PackageName -ne 'com.dshbox.app') {
        throw "Unexpected package name: $($identity.PackageName)"
    }
    if ($identity.VersionCode -ne $expectedVersionCode) {
        throw "Unexpected versionCode: $($identity.VersionCode)"
    }
    if ($identity.VersionName -ne $expectedVersionName) {
        throw "Unexpected versionName: $($identity.VersionName)"
    }
}
$systemSigner = Get-ReleaseApkSigner -Apk $systemFile -ApkSigner $apksigner
$geckoSigner = Get-ReleaseApkSigner -Apk $geckoFile -ApkSigner $apksigner
if ($systemSigner -ne $geckoSigner) {
    throw "Release APK signer mismatch: system=$systemSigner gecko=$geckoSigner"
}

Write-Host 'Release APK contract passed.'
Write-Host "System APK: $($systemFile.FullName)"
Write-Host "Gecko APK:  $($geckoFile.FullName)"
Write-Host "Package:    $($systemIdentity.PackageName) ($($systemIdentity.VersionCode), $($systemIdentity.VersionName))"
Write-Host "Signer SHA-256: $systemSigner"
Write-Host "Shared page compatibility SHA-256: $systemHash"
Write-Host "Expected source SHA-256:          $expectedHash"
Write-Host "System bootstrap SHA-256:         $systemBootstrapHash"
Write-Host "Worker capability SHA-256:        $systemWorkerProbeHash"
Write-Host "Extracted assets: $ExtractDir"

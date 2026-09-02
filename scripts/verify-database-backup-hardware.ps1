[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DatabasePath,

    [Parameter(Mandatory = $true)]
    [string]$BackupRoot,

    [ValidateSet('TEST', 'PRODUCTION')]
    [string]$ExpectedEnvironment = 'TEST'
)

$ErrorActionPreference = 'Stop'

function Get-NormalizedPath([string]$PathValue) {
    return [System.IO.Path]::GetFullPath($PathValue)
}

$database = Get-NormalizedPath $DatabasePath
$backupRoot = Get-NormalizedPath $BackupRoot
if (-not (Test-Path -LiteralPath $database -PathType Leaf)) {
    throw "测试数据库不存在：$database"
}
if (-not (Test-Path -LiteralPath $backupRoot -PathType Container)) {
    throw "备份根目录不存在：$backupRoot"
}
if ($ExpectedEnvironment -eq 'TEST' -and $backupRoot -match '[\\/]LEDGameBackup[\\/]member-admin(?:[\\/]|$)') {
    throw '测试验收不能使用正式备份目录 LEDGameBackup\member-admin。'
}

$driveLetter = [System.IO.Path]::GetPathRoot($database).Substring(0, 1)
$partition = Get-Partition -DriveLetter $driveLetter
$sourceDisk = Get-Disk -Number $partition.DiskNumber
$backupDriveLetter = [System.IO.Path]::GetPathRoot($backupRoot).Substring(0, 1)
$backupPartition = Get-Partition -DriveLetter $backupDriveLetter
$backupDisk = Get-Disk -Number $backupPartition.DiskNumber
$backupVolume = Get-Volume -DriveLetter $backupDriveLetter

Write-Host "源数据库：$database"
Write-Host "源物理盘：Disk $($sourceDisk.Number) / $($sourceDisk.FriendlyName)"
Write-Host "备份根目录：$backupRoot"
Write-Host "备份物理盘：Disk $($backupDisk.Number) / $($backupDisk.FriendlyName)"
if ($backupDisk.Number -eq $sourceDisk.Number) {
    throw '主数据库与备份目录位于同一块物理硬盘，不能通过异盘验收。'
}
if ([string]$backupVolume.DriveType -ne 'Fixed' -or $backupDisk.IsReadOnly) {
    throw '备份目录必须位于可写的本地固定硬盘。'
}

$latestDatabase = Join-Path $backupRoot 'latest\platform.db'
$metadataPath = Join-Path $backupRoot 'latest\metadata.json'
if (-not (Test-Path -LiteralPath $latestDatabase -PathType Leaf) -or
    -not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
    throw '本次测试根目录尚未生成 latest 数据库与元数据。'
}

$metadata = Get-Content -LiteralPath $metadataPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ([string]$metadata.format -ne 'ledgame-platform-backup-v2') {
    throw "latest 元数据格式不是 v2：$($metadata.format)"
}
if ([string]$metadata.environment -ne $ExpectedEnvironment) {
    throw "latest 环境不符：期望 $ExpectedEnvironment，实际 $($metadata.environment)"
}
if ([string]$metadata.integrityCheck -ne 'ok') {
    throw "latest 元数据未记录通过 SQLite integrity_check（$backupRoot）"
}
$headerBytes = New-Object byte[] 16
$headerStream = [System.IO.File]::OpenRead($latestDatabase)
try {
    if ($headerStream.Read($headerBytes, 0, 16) -ne 16) { throw 'latest 文件长度不足。' }
} finally {
    $headerStream.Dispose()
}
$header = [System.Text.Encoding]::ASCII.GetString($headerBytes)
if ($header -ne "SQLite format 3$([char]0)") {
    throw "latest 文件不是有效的 SQLite 文件头（$backupRoot）"
}
$actualHash = (Get-FileHash -LiteralPath $latestDatabase -Algorithm SHA256).Hash.ToLowerInvariant()
$expectedHash = ([string]$metadata.sha256).ToLowerInvariant()
if ($actualHash -ne $expectedHash) {
    throw "latest 校验失败：元数据 SHA-256 与文件不一致（$backupRoot）"
}

$isolatedRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("LEDGameBackupAcceptance\" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $isolatedRoot -Force | Out-Null
$isolatedRestore = Join-Path $isolatedRoot 'restored-platform.db'
Copy-Item -LiteralPath $latestDatabase -Destination $isolatedRestore
$restoredHash = (Get-FileHash -LiteralPath $isolatedRestore -Algorithm SHA256).Hash.ToLowerInvariant()
if ($restoredHash -ne $expectedHash) {
    throw '隔离恢复副本校验失败。'
}

Write-Host "备份环境：$($metadata.environment)"
Write-Host "备份 revision：$($metadata.revision)"
Write-Host "最后业务修改时间：$($metadata.lastBusinessModifiedAt)"
Write-Host "SHA-256：$actualHash"
Write-Host "隔离恢复副本：$isolatedRestore"
Write-Host '验收结果：跨物理盘关系、测试/正式环境、latest 元数据、文件哈希和隔离恢复副本均通过。'

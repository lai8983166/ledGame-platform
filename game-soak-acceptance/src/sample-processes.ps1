param([Parameter(Mandatory=$true)][int]$RootPid, [string]$ExpectedIdentitiesJson)
$ErrorActionPreference = 'Stop'
$allProcesses = @(Get-CimInstance Win32_Process)
$expected = if ($ExpectedIdentitiesJson) { @($ExpectedIdentitiesJson | ConvertFrom-Json) } else { @() }
if ($ExpectedIdentitiesJson) {
    $remaining = @($allProcesses | Where-Object {
        $candidate = $_
        @($expected | Where-Object { $_.pid -eq $candidate.ProcessId -and $_.createdAt -eq $candidate.CreationDate.ToUniversalTime().ToString('o') }).Count -gt 0
    } | ForEach-Object { [pscustomobject]@{ pid = [int]$_.ProcessId; createdAt = $_.CreationDate.ToUniversalTime().ToString('o') } })
    ConvertTo-Json -InputObject $remaining -Compress
    exit 0
}
$ownedIds = [System.Collections.Generic.HashSet[int]]::new()
[void]$ownedIds.Add($RootPid)
do {
    $added = $false
    foreach ($row in $allProcesses) {
        if ($ownedIds.Contains([int]$row.ParentProcessId) -and -not $ownedIds.Contains([int]$row.ProcessId)) {
            [void]$ownedIds.Add([int]$row.ProcessId)
            $added = $true
        }
    }
} while ($added)
$rows = @(
    foreach ($row in $allProcesses) {
        if (-not $ownedIds.Contains([int]$row.ProcessId)) { continue }
        $process = Get-Process -Id $row.ProcessId -ErrorAction SilentlyContinue
        if ($null -eq $process) { continue }
        $role = 'utility'
        if ($row.ProcessId -eq $RootPid) { $role = 'main' }
        elseif ($row.Name -match '^javaw?\.exe$') { $role = 'java' }
        elseif ($row.CommandLine -match '--type=renderer') { $role = 'renderer' }
        [pscustomobject]@{
            pid = [int]$row.ProcessId
            parentPid = [int]$row.ParentProcessId
            createdAt = $row.CreationDate.ToUniversalTime().ToString('o')
            role = $role
            privateBytes = [long]$process.PrivateMemorySize64
            workingSetBytes = [long]$process.WorkingSet64
            responding = [bool]$process.Responding
        }
    }
)
ConvertTo-Json -InputObject $rows -Compress

param(
    [string] $Report = (Join-Path $PSScriptRoot "build\last_runtime_report.txt")
)

function Write-Check {
    param(
        [string] $Status,
        [string] $Name,
        [string] $Detail = ""
    )

    $suffix = if ($Detail) { " - $Detail" } else { "" }
    Write-Host ("[{0}] {1}{2}" -f $Status, $Name, $suffix)
}

function Has-Pattern {
    param([string] $Pattern)
    return [regex]::IsMatch($script:Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
}

function Count-Pattern {
    param([string] $Pattern)
    return ([regex]::Matches($script:Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)).Count
}

function Last-Line {
    param([string] $Pattern)
    return $script:Lines | Where-Object { $_ -match $Pattern } | Select-Object -Last 1
}

function Write-Last {
    param(
        [string] $Name,
        [string] $Pattern
    )

    $line = Last-Line $Pattern
    if ($line) {
        Write-Check "INFO" $Name ($line.Trim())
    } else {
        Write-Check "MISS" $Name
    }
}

function Get-SectionText {
    param([string] $Header)

    $headerLine = "==== $Header ===="
    $start = -1
    for ($i = 0; $i -lt $script:Lines.Count; $i++) {
        if ($script:Lines[$i].Trim() -eq $headerLine) {
            $start = $i + 1
            break
        }
    }
    if ($start -lt 0) {
        return ""
    }

    $end = $script:Lines.Count
    for ($i = $start; $i -lt $script:Lines.Count; $i++) {
        if ($script:Lines[$i] -match "^==== .+ ====$") {
            $end = $i
            break
        }
    }
    if ($end -le $start) {
        return ""
    }
    return ($script:Lines[$start..($end - 1)] -join "`n")
}

function Section-IsMissing {
    param([string] $SectionText)
    return $SectionText -match "(?m)^missing$"
}

if (-not (Test-Path -LiteralPath $Report)) {
    Write-Check "FAIL" "Report file" ("missing: {0}" -f [System.IO.Path]::GetFileName($Report))
    exit 2
}

$script:Text = [System.IO.File]::ReadAllText($Report)
$script:Lines = $script:Text -split "`r?`n"
$nativeSection = Get-SectionText "Native loader log"
$javaSection = Get-SectionText "Java bootstrap log"

Write-Host "Xenobyte Modern Report Analysis"
Write-Host ("Report: {0}" -f [System.IO.Path]::GetFileName($Report))
Write-Host ""

$dllLine = $script:Lines | Where-Object { $_ -like "Dll=*" } | Select-Object -First 1
if ($dllLine) {
    Write-Check "INFO" "Build DLL" ($dllLine -replace "^Dll=", "")
} else {
    Write-Check "MISS" "Build DLL" "no Dll= line"
}

if (Section-IsMissing $nativeSection) {
    Write-Check "MISS" "Native live log" "missing; inject has not been reported since logs were cleared"
} else {
    Write-Check "OK" "Native live log" "present"
}

if (Section-IsMissing $javaSection) {
    Write-Check "MISS" "Java live log" "missing; inject has not been reported since logs were cleared"
} else {
    Write-Check "OK" "Java live log" "present"
}

Write-Host ""
Write-Host "Bootstrap"
$bootstrapChecks = @(
    @("Using jar", "Using jar:"),
    @("Runtime jar copy", "Runtime jar copy:"),
    @("Native bootstrap invoke", "Bootstrap invoked successfully"),
    @("Native DLL self-unload", "Native loader thread finished; unloading DLL module"),
    @("NativeBootstrap start", "NativeBootstrap start"),
    @("Forge environment", "Forge runtime environment OK"),
    @("Forge event listeners", "Forge direct event listeners registered"),
    @("Runtime generation", "Runtime generation activated:"),
    @("Bootstrap completed", "Bootstrap completed:"),
    @("Client tick", "First client tick callback observed"),
    @("HUD callback", "First HUD callback observed"),
    @("World render callback", "First world render callback observed"),
    @("GUI open", "GUI opened via raw GLFW fallback path")
)

foreach ($check in $bootstrapChecks) {
    if (Has-Pattern ([regex]::Escape($check[1]))) {
        Write-Check "OK" $check[0]
    } else {
        Write-Check "MISS" $check[0]
    }
}

Write-Host ""
Write-Host "Modules"
$modulePerformed = Count-Pattern "Module performed:"
Write-Check ($(if ($modulePerformed -gt 0) { "OK" } else { "MISS" })) "Module toggles/actions" ("count={0}" -f $modulePerformed)

$bindEvents = Count-Pattern "Bind assigned:|Bind cleared:|Bind conflict cleared:"
Write-Check ($(if ($bindEvents -gt 0) { "OK" } else { "INFO" })) "Bind events" ("count={0}" -f $bindEvents)

$xrayScans = Count-Pattern "XRay scan completed:"
$xrayRender = Count-Pattern "XRay render path active:"
Write-Check ($(if ($xrayScans -gt 0) { "OK" } else { "MISS" })) "XRay scan logs" ("count={0}" -f $xrayScans)
Write-Check ($(if ($xrayRender -gt 0) { "OK" } else { "MISS" })) "XRay render logs" ("count={0}" -f $xrayRender)

$espCounters = Count-Pattern "Esp render counters:"
$espRender = Count-Pattern "Esp render path active:"
Write-Check ($(if ($espCounters -gt 0) { "OK" } else { "MISS" })) "ESP counter logs" ("count={0}" -f $espCounters)
Write-Check ($(if ($espRender -gt 0) { "OK" } else { "MISS" })) "ESP render logs" ("count={0}" -f $espRender)

$blockOverlay = Count-Pattern "BlockOverlay target:"
Write-Check ($(if ($blockOverlay -gt 0) { "OK" } else { "INFO" })) "BlockOverlay target logs" ("count={0}" -f $blockOverlay)

$critTriggers = Count-Pattern "Crit trigger:"
Write-Check ($(if ($critTriggers -gt 0) { "OK" } else { "INFO" })) "Crit trigger logs" ("count={0}" -f $critTriggers)

$nofallActions = Count-Pattern "NoFall tick action:"
Write-Check ($(if ($nofallActions -gt 0) { "OK" } else { "INFO" })) "NoFall interventions" ("count={0}" -f $nofallActions)

$airJumps = Count-Pattern "AirJumps forced local ground:"
Write-Check ($(if ($airJumps -gt 0) { "OK" } else { "INFO" })) "AirJumps interventions" ("count={0}" -f $airJumps)

$flyTicks = Count-Pattern "Fly tick:|Fly stored previous abilities|Fly restored previous abilities|Fly creative double-space toggled:"
Write-Check ($(if ($flyTicks -gt 0) { "OK" } else { "INFO" })) "Fly logs" ("count={0}" -f $flyTicks)

$ceilingMoves = Count-Pattern "Ceiling moved:|Ceiling stepped finish:"
$ceilingFailures = Count-Pattern "Ceiling failed:"
$ceilingSteps = Count-Pattern "Ceiling stepped move started:|Ceiling stepped pulse:"
$ceilingFallbacks = Count-Pattern "Ceiling rollback detected:|Ceiling stepped fallback to instant:"
Write-Check ($(if (($ceilingMoves + $ceilingFailures + $ceilingSteps + $ceilingFallbacks) -gt 0) { "OK" } else { "INFO" })) "Ceiling attempts" ("moved={0}, failed={1}, steps={2}, rollback/fallback={3}" -f $ceilingMoves, $ceilingFailures, $ceilingSteps, $ceilingFallbacks)

$autoSpawn = Count-Pattern "AutoSpawn command sent:"
Write-Check ($(if ($autoSpawn -gt 0) { "OK" } else { "INFO" })) "AutoSpawn commands" ("count={0}" -f $autoSpawn)

$checkVanish = Count-Pattern "CheckVanish result:"
Write-Check ($(if ($checkVanish -gt 0) { "OK" } else { "INFO" })) "CheckVanish results" ("count={0}" -f $checkVanish)

$xraySelect = Count-Pattern "XRaySelect added:|XRaySelect removed:|XRaySelect failed:"
Write-Check ($(if ($xraySelect -gt 0) { "OK" } else { "INFO" })) "XRaySelect actions" ("count={0}" -f $xraySelect)

$panic = Count-Pattern "Runtime shutdown completed:|Fallback poller stopped;"
Write-Check ($(if ($panic -gt 0) { "OK" } else { "INFO" })) "Panic/shutdown" ("count={0}" -f $panic)

Write-Host ""
Write-Host "Last Observed Events"
Write-Last "Last native loader event" "Using jar:|Runtime jar copy:|Bootstrap invoked successfully|Native loader thread finished"
Write-Last "Last bootstrap status" "Bootstrap completed:|Runtime shutdown completed:|Fallback poller stopped;"
Write-Last "Last module action" "Module performed:"
Write-Last "Last bind event" "Bind assigned:|Bind cleared:|Bind conflict cleared:"
Write-Last "Last XRay scan" "XRay scan completed:"
Write-Last "Last XRay render" "XRay render path active:"
Write-Last "Last ESP counters" "Esp render counters:"
Write-Last "Last ESP render" "Esp render path active:"
Write-Last "Last BlockOverlay target" "BlockOverlay target:"
Write-Last "Last Crit trigger" "Crit trigger:"
Write-Last "Last NoFall action" "NoFall tick action:"
Write-Last "Last AirJumps action" "AirJumps forced local ground:"
Write-Last "Last Fly event" "Fly tick:|Fly stored previous abilities|Fly restored previous abilities|Fly creative double-space toggled:"
Write-Last "Last Ceiling result" "Ceiling moved:|Ceiling failed:|Ceiling stepped finish:|Ceiling stepped pulse:|Ceiling rollback detected:|Ceiling stepped fallback to instant:"
Write-Last "Last AutoSpawn command" "AutoSpawn command sent:"
Write-Last "Last CheckVanish result" "CheckVanish result:"
Write-Last "Last XRaySelect action" "XRaySelect added:|XRaySelect removed:|XRaySelect failed:"
Write-Last "Last panic/shutdown" "Runtime shutdown completed:|Fallback poller stopped;"

Write-Host ""
Write-Host "Problems"
$fatalPatterns = @(
    "jvm\.dll is not loaded in this process",
    "No created Java VM found",
    "Could not find a classloader that can resolve Minecraft/Forge classes",
    "NativeBootstrap failed",
    "Bootstrap failed",
    "JNI exception"
)

$fatalCount = 0
foreach ($pattern in $fatalPatterns) {
    $count = Count-Pattern $pattern
    if ($count -gt 0) {
        $fatalCount += $count
        Write-Check "WARN" $pattern ("count={0}" -f $count)
    }
}

if ($fatalCount -eq 0) {
    Write-Check "OK" "No known fatal markers"
}

Write-Host ""
if (Section-IsMissing $javaSection) {
    Write-Host "Result: WAITING_FOR_LIVE_INJECT"
} elseif ($fatalCount -gt 0) {
    Write-Host "Result: CHECK_WARNINGS"
} else {
    Write-Host "Result: REPORT_HAS_RUNTIME_DATA"
}

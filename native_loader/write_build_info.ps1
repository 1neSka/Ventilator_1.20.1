param(
    [Parameter(Mandatory = $true)]
    [string] $Jar,

    [Parameter(Mandatory = $true)]
    [string] $Dll,

    [Parameter(Mandatory = $true)]
    [string] $Out
)

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string] $Path)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $hash = $sha.ComputeHash($stream)
        return -join ($hash | ForEach-Object { $_.ToString("X2") })
    } finally {
        $stream.Dispose()
        $sha.Dispose()
    }
}

$outPath = [System.IO.Path]::GetFullPath($Out)
$outDirectory = Split-Path -Parent $outPath
$jarPath = [System.IO.Path]::GetFullPath($Jar)
$dllPath = [System.IO.Path]::GetFullPath($Dll)

function Get-PortablePath {
    param([Parameter(Mandatory = $true)][string] $Path)

    $baseUri = [System.Uri]::new(([System.IO.Path]::GetFullPath($outDirectory).TrimEnd("\") + "\"))
    $pathUri = [System.Uri]::new([System.IO.Path]::GetFullPath($Path))
    $relative = $baseUri.MakeRelativeUri($pathUri).ToString()
    return [System.Uri]::UnescapeDataString($relative).Replace("/", "\")
}

$lines = @(
    "Built=$([DateTime]::Now.ToString('o'))",
    "Jar=$(Get-PortablePath -Path $jarPath)",
    "JarSHA256=$(Get-Sha256 -Path $jarPath)",
    "Dll=$(Get-PortablePath -Path $dllPath)",
    "DllSHA256=$(Get-Sha256 -Path $dllPath)"
)

[System.IO.File]::WriteAllLines($outPath, $lines, [System.Text.Encoding]::ASCII)

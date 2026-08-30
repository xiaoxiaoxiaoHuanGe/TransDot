param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [string[]]$ExpectedAbis = @("arm64-v8a")
)

$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($resolvedApk)

try {
    $actualAbis = @(
        $archive.Entries |
            Where-Object { $_.FullName -like "lib/*/*.so" } |
            ForEach-Object { ($_.FullName -split "/")[1] } |
            Sort-Object -Unique
    )
} finally {
    $archive.Dispose()
}

$expected = @($ExpectedAbis | Sort-Object -Unique)
if ($actualAbis.Count -eq 0) {
    throw "APK contains no native ABI libraries: $resolvedApk"
}

$difference = Compare-Object -ReferenceObject $expected -DifferenceObject $actualAbis
if ($difference) {
    throw "Unexpected APK ABIs. Expected: $($expected -join ', '); actual: $($actualAbis -join ', ')"
}

Write-Output "Verified APK ABIs: $($actualAbis -join ', ')"

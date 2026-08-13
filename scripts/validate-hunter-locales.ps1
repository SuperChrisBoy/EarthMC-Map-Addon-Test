$ErrorActionPreference = 'Stop'
$langDir = Join-Path $PSScriptRoot '..\src\main\resources\assets\townymapaddon\lang'
$englishObject = Get-Content -Raw (Join-Path $langDir 'en_us.json') | ConvertFrom-Json
$english = @{}; $englishObject.PSObject.Properties | ForEach-Object { $english[$_.Name] = $_.Value }
$hunterKeys = @($english.Keys | Where-Object { $_ -like 'townymapaddon.hunter.*' -or $_ -like 'townymapaddon.teleport.*' } | Sort-Object)
if ($hunterKeys.Count -eq 0) { throw 'en_us.json contains no feature translation keys.' }

function Get-Placeholders([string] $value) {
    return @([regex]::Matches($value, '%(?:\d+\$)?s') | ForEach-Object Value | Sort-Object)
}

foreach ($file in Get-ChildItem $langDir -Filter '*.json') {
    $raw = Get-Content -Raw $file.FullName
    $jsonKeys = @([regex]::Matches($raw, '(?m)^\s*"((?:\\.|[^"])*)"\s*:') | ForEach-Object { $_.Groups[1].Value })
    $duplicates = @($jsonKeys | Group-Object | Where-Object Count -gt 1)
    if ($duplicates.Count -gt 0) { throw "$($file.Name): duplicate JSON key(s): $($duplicates.Name -join ', ')" }
    $object = $raw | ConvertFrom-Json
    $data = @{}; $object.PSObject.Properties | ForEach-Object { $data[$_.Name] = $_.Value }
    foreach ($key in $hunterKeys) {
        if (-not $data.ContainsKey($key)) { throw "$($file.Name): missing $key" }
        $expected = (Get-Placeholders ([string]$english[$key])) -join ','
        $actual = (Get-Placeholders ([string]$data[$key])) -join ','
        if ($expected -ne $actual) { throw "$($file.Name): incompatible placeholders for $key ($actual; expected $expected)" }
    }
}
Write-Host "Validated $($hunterKeys.Count) Hunter/Teleport keys in $((Get-ChildItem $langDir -Filter '*.json').Count) locale files."

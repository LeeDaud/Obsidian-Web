param(
    [string]$Apk = (Join-Path $PSScriptRoot 'app/build/outputs/apk/release/app-release.apk'),
    [string]$ReleaseNotes = '改进本机记录体验，支持应用内正式覆盖更新。',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidHome = $env:ANDROID_HOME
)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome -or -not $AndroidHome) { throw 'Existing JAVA_HOME and ANDROID_HOME are required.' }
$aapt = Join-Path $AndroidHome 'build-tools/35.0.0/aapt.exe'
$signer = Join-Path $AndroidHome 'build-tools/35.0.0/apksigner.bat'
$certificate = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Echo/signing/certificate.cer'
foreach ($file in @($Apk, $aapt, $signer, $certificate)) { if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw 'Update packaging prerequisites are missing.' } }
$oldJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaHome
    $signatures = (& $signer verify --verbose --print-certs $Apk 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $expected = (Get-FileHash -LiteralPath $certificate -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($signatures -notmatch 'Signer #1 certificate SHA-256 digest: ([a-f0-9]{64})' -or $Matches[1] -ne $expected -or $signatures -notmatch 'Number of signers: 1') { throw 'APK must use the original Echo release certificate.' }
    $badging = (& $aapt dump badging $Apk 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $badging -match 'application-debuggable' -or $badging -notmatch "package: name='xyz.leedaud.echo' versionCode='(\d+)' versionName='([^']+)'") { throw 'Not a valid Echo release APK.' }
    $code = [long]$Matches[1]; $version = $Matches[2]
    if ($badging -notmatch "sdkVersion:'(\d+)'") { throw 'Missing minimum SDK.' }
    $minimum = [int]$Matches[1]
    if ($code -lt 1 -or $code -gt [int]::MaxValue -or $version.Length -gt 80 -or $ReleaseNotes.Length -gt 12000) { throw 'Update metadata is outside limits.' }
    $source = Get-Item -LiteralPath $Apk
    if ($source.Length -gt 100MB) { throw 'APK exceeds the updater download limit.' }
    $asset = 'Echo-' + ($version -replace '[^A-Za-z0-9._-]', '-') + '-build' + $code + '.apk'
    $target = Join-Path $source.DirectoryName $asset
    if ($source.FullName -ne $target) { Copy-Item -LiteralPath $source.FullName -Destination $target }
    $manifest = [ordered]@{
        schemaVersion = 1; applicationId = 'xyz.leedaud.echo'; versionCode = $code; versionName = $version
        minSdk = $minimum; apkAssetName = $asset; size = $source.Length
        sha256 = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant(); releaseNotes = $ReleaseNotes
    }
    $output = Join-Path $source.DirectoryName 'echo-update.json'
    [IO.File]::WriteAllText($output, ($manifest | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    Write-Output "Prepared local release assets: $asset and echo-update.json. No upload or publication performed."
} finally { $env:JAVA_HOME = $oldJava }

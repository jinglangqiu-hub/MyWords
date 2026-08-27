Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$keystorePath = Join-Path $projectRoot "release-key.jks"
$propertiesPath = Join-Path $projectRoot "keystore.properties"
$alias = "wordreview-release"

if ((Test-Path -LiteralPath $keystorePath) -or (Test-Path -LiteralPath $propertiesPath)) {
    throw "Release signing files already exist. Refusing to overwrite them."
}

$keytoolCandidates = @()
if ($env:JAVA_HOME) {
    $keytoolCandidates += (Join-Path $env:JAVA_HOME "bin\keytool.exe")
}
$keytoolCandidates += "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
$keytoolPath = $keytoolCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $keytoolPath) {
    throw "keytool.exe was not found. Install Android Studio or set JAVA_HOME."
}

$passwordBytes = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
$password = [Convert]::ToHexString($passwordBytes)
$process = $null

try {
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $keytoolPath
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.CreateNoWindow = $true
    $startInfo.Environment["WORDREVIEW_KEYSTORE_PASSWORD"] = $password

    $arguments = @(
        "-genkeypair",
        "-keystore", $keystorePath,
        "-storetype", "PKCS12",
        "-storepass:env", "WORDREVIEW_KEYSTORE_PASSWORD",
        "-keypass:env", "WORDREVIEW_KEYSTORE_PASSWORD",
        "-alias", $alias,
        "-keyalg", "RSA",
        "-keysize", "4096",
        "-sigalg", "SHA256withRSA",
        "-validity", "10000",
        "-dname", "CN=QIU, OU=CQU, O=CQU, L=CHONGQING, ST=CHONGQING, C=CN"
    )
    foreach ($argument in $arguments) {
        [void]$startInfo.ArgumentList.Add($argument)
    }

    $process = [System.Diagnostics.Process]::Start($startInfo)
    $standardOutput = $process.StandardOutput.ReadToEnd()
    $standardError = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if ($process.ExitCode -ne 0) {
        throw "keytool failed with exit code $($process.ExitCode): $standardError $standardOutput"
    }

    $properties = @(
        "storeFile=release-key.jks",
        "storePassword=$password",
        "keyAlias=$alias",
        "keyPassword=$password"
    ) -join [Environment]::NewLine
    [System.IO.File]::WriteAllText($propertiesPath, $properties + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))

    Write-Output "Created release-key.jks and keystore.properties."
    Write-Output "Alias: $alias"
}
catch {
    if (Test-Path -LiteralPath $propertiesPath) {
        Remove-Item -LiteralPath $propertiesPath -Force
    }
    if (Test-Path -LiteralPath $keystorePath) {
        Remove-Item -LiteralPath $keystorePath -Force
    }
    throw
}
finally {
    if ($process) {
        $process.Dispose()
    }
    [Array]::Clear($passwordBytes, 0, $passwordBytes.Length)
    $password = $null
}

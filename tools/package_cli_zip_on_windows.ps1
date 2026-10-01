# Builds the command line's zip for Windows on x86-64 on Windows: dog-vision-cli, to unpack and run
# anywhere without installing it.
#
# jpackage makes its app image with dog-vision-cli.exe, a native launcher that runs in a console,
# beside the JAR that cli's uberJar task assembles and the runtime that its windowsRuntime task links
# from Temurin's jmods for Windows; the zip holds that image and the licence. It is written to
# cli/build/packages/zip. tools/package_cli_zip_on_linux.sh builds the same zip through Wine.
#
# Needs:
# - PowerShell 7 (pwsh), which reads this file as UTF-8, as the vendor's name needs.
# - A JDK 25 as JAVA_HOME, whose jpackage makes the app image.
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$cli = Join-Path $root "cli"

# Runs a program and stops the script where it fails, as $ErrorActionPreference does not for them.
function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Program failed with $LASTEXITCODE"
    }
}

if (-not $env:JAVA_HOME) {
    throw "JAVA_HOME names no JDK"
}


# What jpackage takes in: the JAR and the runtime.

$properties = Get-Content (Join-Path $root "gradle.properties") -Raw
if ($properties -notmatch '(?m)^appVersion=(.+?)\r?$') {
    throw "gradle.properties has no appVersion"
}
$version = $Matches[1]
Invoke-Checked (Join-Path $root "gradlew.bat") @("--quiet", ":cli:uberJar", ":cli:windowsRuntime")

$staging = Join-Path $cli "build\windows-zip"
if (Test-Path $staging) {
    Remove-Item -Recurse -Force $staging
}

# jpackage takes every file in --input into the application, so the JAR goes there alone.
$inputDir = Join-Path $staging "input"
New-Item -ItemType Directory -Path $inputDir | Out-Null
Copy-Item (Join-Path $cli "build\jars\dog-vision-cli.jar") $inputDir


# jpackage, and the zip of its image.

$image = Join-Path $staging "image"
$arguments = @(
    "--type", "app-image",
    "--name", "dog-vision-cli",
    "--app-version", $version,
    "--vendor", "Martin Lopatář",
    "--description", "How a dog or another animal sees a photo, from the command line",
    "--icon", (Join-Path $root "gui-compose\packaging\dog-vision.ico"),
    "--input", $inputDir,
    "--main-jar", "dog-vision-cli.jar",
    "--main-class", "cz.loplex.dogvision.cli.MainKt",
    "--runtime-image", (Join-Path $cli "build\windows\runtime"),
    "--win-console",
    "--dest", $image
)

# From a file in UTF-8, which jpackage reads as its default charset: Java reads its command line in
# the system's ANSI code page, which on an English Windows, 1252, has no ř for the vendor's name.
# Each argument in quotes, inside which a backslash escapes the next character.
$argumentFile = Join-Path $staging "jpackage-arguments"
$quoted = $arguments | ForEach-Object { '"' + ($_ -replace '\\', '\\' -replace '"', '\"') + '"' }
[System.IO.File]::WriteAllLines($argumentFile, [string[]]$quoted, [System.Text.UTF8Encoding]::new($false))
Invoke-Checked (Join-Path $env:JAVA_HOME "bin\jpackage.exe") @("@$argumentFile")

$app = Join-Path $image "dog-vision-cli"
Copy-Item (Join-Path $root "LICENSE") $app
$output = Join-Path $cli "build\packages\zip"
New-Item -ItemType Directory -Force -Path $output | Out-Null
$zip = Join-Path $output "dog-vision-cli-$version-windows-x64.zip"
if (Test-Path $zip) {
    Remove-Item -Force $zip
}
# The image's folder at the zip's root, so that it unpacks into a folder of its own.
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($app, $zip, [System.IO.Compression.CompressionLevel]::Optimal, $true)

$zip

# Builds a desktop window's MSI for Windows on x86-64 on Windows: with -Window compose, the default,
# dog-vision's, the Compose window's; with -Window swing, dog-vision-swing's. With -Window cli it
# builds dog-vision-cli's, the command line's alone, as tools/package_msi_on_linux.sh says, which
# puts its folder on the system's PATH.
#
# The same MSI as tools/package_msi_on_linux.sh builds through Wine, of the arguments and the
# resource directory that the window's windowsJpackage task writes, which say what it is made of,
# with the JAR that its windowsUberJar task assembles and the runtime that its windowsRuntime task
# links, but with the jpackage of the JDK this runs on, which makes the MSI through WiX Toolset 3,
# whose light.exe validates it (ICE) here. It is written to tools\build\msi, which git ignores, as
# tools\build\msi\dog-vision-0.1.0.msi.
#
# Needs:
# - PowerShell 7 (pwsh), which reads this file as UTF-8.
# - A JDK 25 as JAVA_HOME, for its jpackage.
# - WiX Toolset 3.14's candle.exe and light.exe on the PATH, or its installation's WIX variable.
#
# -AppVersion gives the MSI another version than gradle.properties' appVersion, as a
# test of an upgrade needs a later one; the application in it stays the same.
param(
    [string]$AppVersion,
    [ValidateSet("compose", "swing", "cli")] [string]$Window = "compose"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot

# Each window's module, whose build script says what its MSI is made of, and the MSI's name.
if ($Window -eq "compose") {
    $module = "gui-compose"
    $name = "dog-vision"
} elseif ($Window -eq "swing") {
    $module = "gui-swing"
    $name = "dog-vision-swing"
} else {
    $module = "cli"
    $name = "dog-vision-cli"
}

# Runs a program and stops the script where it fails, as $ErrorActionPreference does not for them.
function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Program failed with $LASTEXITCODE"
    }
}

if (-not (Get-Command "light.exe" -ErrorAction SilentlyContinue)) {
    if (-not $env:WIX) {
        throw "WiX Toolset 3 is neither on the PATH nor named by WIX"
    }
    $env:PATH = "$(Join-Path $env:WIX "bin");$env:PATH"
}
if (-not $env:JAVA_HOME) {
    throw "JAVA_HOME names no JDK"
}
$jdkBin = Join-Path $env:JAVA_HOME "bin"


# What jpackage takes in: the arguments, with the JAR and the runtime they name, and the resource
# directory, all of which windowsJpackage writes.

$properties = Get-Content (Join-Path $root "gradle.properties") -Raw
if ($properties -notmatch '(?m)^appVersion=(.+?)\r?$') {
    throw "gradle.properties has no appVersion"
}
$gradleOptions = @("--quiet", ":${module}:windowsJpackage", "-PjpackageJdk=$env:JAVA_HOME")
if ($AppVersion) {
    $gradleOptions += "-PwindowsAppVersion=$AppVersion"
} else {
    $AppVersion = $Matches[1]
}
Invoke-Checked (Join-Path $root "gradlew.bat") $gradleOptions
$arguments = Join-Path $root "$module\build\windows\jpackage"

$staging = Join-Path $root "tools\build\staging\$name-msi"
if (Test-Path $staging) {
    Remove-Item -Recurse -Force $staging
}
$output = Join-Path $root "tools\build\msi"
$msi = Join-Path $output "$name-$AppVersion.msi"
if (Test-Path $msi) {
    Remove-Item -Force $msi
}


# jpackage, which reads the files of arguments in its default charset, UTF-8 from JDK 18 on, as
# windowsJpackage writes them: Java reads its command line in the system's ANSI code page, which on
# an English Windows, 1252, has no ř for the vendor's name.

Invoke-Checked (Join-Path $jdkBin "jpackage.exe") @(
    "@$(Join-Path $arguments "package-arguments")",
    "@$(Join-Path $arguments "image-arguments")",
    "@$(Join-Path $arguments "msi-arguments")",
    "--type", "msi",
    "--temp", (Join-Path $staging "temp"),
    "--dest", $output,
    # What light.exe says where it fails, which jpackage prints only then.
    "--verbose"
)

$msi

# Builds a desktop window's MSI for Windows on x86-64 on Windows: with -Window compose, the default,
# dog-vision's, the Compose window's; with -Window swing, dog-vision-swing's. With -Window cli it
# builds dog-vision-cli's, the command line's alone, as tools/package_msi_on_linux.sh says, which
# puts its folder on the system's PATH.
#
# The same MSI as tools/package_msi_on_linux.sh builds through Wine, from the JAR that the window's
# windowsUberJar task assembles and the runtime that its windowsRuntime task links, but with the
# jpackage of the JDK this runs on, which makes the MSI through WiX Toolset 3, whose light.exe
# validates it (ICE) here. It is
# written to gui-compose/build/compose/binaries/main/msi/<version>, gui-swing/build/packages/msi/<version> or
# cli/build/packages/msi/<version>.
#
# Needs:
# - PowerShell 7 (pwsh), which reads this file as UTF-8, as the vendor's name needs.
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
# The icons and dog-vision-cli's properties, which both windows' MSIs take.
$packaging = Join-Path $root "gui-compose\packaging"

# What each window's MSI is made of, as package_msi_on_linux.sh gives it, which says why the upgrade
# codes must never change and must differ, and where the Java option comes from. The JAR's name
# takes the version for {0}.
if ($Window -eq "compose") {
    $name = "dog-vision"
    $module = Join-Path $root "gui-compose"
    $jarTask = ":gui-compose:windowsUberJar"
    $runtimeTask = ":gui-compose:windowsRuntime"
    $jarFile = "build\compose\jars\dog-vision-windows-x64-{0}.jar"
    $outputDir = Join-Path $module "build\compose\binaries\main\msi"
    $mainClass = "cz.loplex.dogvision.desktop.MainKt"
    $description = "How a dog or another animal sees a photo, a video or the camera"
    $upgradeUuid = "602aa86b-3230-4786-8460-ba08bca42e45"
    $javaOptions = @("--java-options", "-Dcompose.application.configure.swing.globals=true")
} elseif ($Window -eq "swing") {
    $name = "dog-vision-swing"
    $module = Join-Path $root "gui-swing"
    $jarTask = ":gui-swing:windowsUberJar"
    $runtimeTask = ":gui-swing:windowsRuntime"
    $jarFile = "build\jars\dog-vision-swing-windows-x64-{0}.jar"
    $outputDir = Join-Path $module "build\packages\msi"
    $mainClass = "cz.loplex.dogvision.swing.MainKt"
    $description = "How a dog or another animal sees a photo, a video or the camera, in Java Swing"
    $upgradeUuid = "acf6164b-f4f9-4430-b4f0-939242f187fb"
    $javaOptions = @()
} else {
    $name = "dog-vision-cli"
    $module = Join-Path $root "cli"
    $jarTask = ":cli:uberJar"
    $runtimeTask = ":cli:windowsRuntime"
    $jarFile = "build\jars\dog-vision-cli.jar"
    $outputDir = Join-Path $module "build\packages\msi"
    $mainClass = "cz.loplex.dogvision.cli.MainKt"
    $description = "How a dog or another animal sees a photo, from the command line"
    $upgradeUuid = "bedc25f5-bde3-4837-86b0-26c5291beed3"
    $javaOptions = @()
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


# What jpackage takes in: the JAR and the runtime, which the module's build\windows\runtime holds.

$properties = Get-Content (Join-Path $root "gradle.properties") -Raw
if ($properties -notmatch '(?m)^appVersion=(.+?)\r?$') {
    throw "gradle.properties has no appVersion"
}
$packageVersion = $Matches[1]
if (-not $AppVersion) {
    $AppVersion = $packageVersion
}
Invoke-Checked (Join-Path $root "gradlew.bat") @("--quiet", $jarTask, $runtimeTask)
$jar = Join-Path $module ($jarFile -f $packageVersion)
$runtime = Join-Path $module "build\windows\runtime"

$staging = Join-Path $module "build\windows-msi\$AppVersion"
if (Test-Path $staging) {
    Remove-Item -Recurse -Force $staging
}

# jpackage takes every file in --input into the application, so the JAR goes there alone.
$inputDir = Join-Path $staging "input"
New-Item -ItemType Directory -Path $inputDir | Out-Null
Copy-Item $jar $inputDir

# The resource directory, less its code page file, which jpackage from JDK 25 on hands light.exe
# after its own English strings, whose code page, 1252, light.exe then takes: in its place, those
# strings of this JDK's jpackage with the code page 1250, which replace jpackage's by their name.
$resources = Join-Path $staging "resources"
Copy-Item -Recurse (Join-Path $packaging "windows") $resources
Remove-Item (Join-Path $resources "MsiInstallerCodepage_en.wxl")
$extracted = Join-Path $staging "jimage"
Invoke-Checked (Join-Path $jdkBin "jimage.exe") @(
    "extract", "--dir", $extracted, "--include", "regex:.*/MsiInstallerStrings_en\.wxl",
    (Join-Path $env:JAVA_HOME "lib\modules")
)
$strings = Get-ChildItem -Recurse -File $extracted | Select-Object -First 1
if (-not $strings) {
    throw "This JDK's jpackage has no MsiInstallerStrings_en.wxl"
}
$text = [System.IO.File]::ReadAllText($strings.FullName)
if ($text -notmatch 'Codepage="1252"') {
    throw "jpackage's MsiInstallerStrings_en.wxl names no code page 1252"
}
$text = $text -replace 'Codepage="1252"', 'Codepage="1250"'
[System.IO.File]::WriteAllText((Join-Path $resources "MsiInstallerStrings_en.wxl"), $text)

# For the command line, this JDK's main.wxs with msi-path.xml in it, as package_msi_on_linux.sh puts
# it there.
if ($Window -eq "cli") {
    Invoke-Checked (Join-Path $jdkBin "jimage.exe") @(
        "extract", "--dir", $extracted, "--include", "regex:.*/jdk/jpackage/internal/resources/main\.wxs",
        (Join-Path $env:JAVA_HOME "lib\modules")
    )
    $mainWxs = Get-ChildItem -Recurse -File -Filter "main.wxs" $extracted | Select-Object -First 1
    if (-not $mainWxs) {
        throw "This JDK's jpackage has no main.wxs"
    }
    $text = [System.IO.File]::ReadAllText($mainWxs.FullName)
    $filesReference = '<ComponentGroupRef Id="Files"/>'
    foreach ($anchor in $filesReference, "</Wix>") {
        if (([regex]::Matches($text, [regex]::Escape($anchor))).Count -ne 1) {
            throw "This JDK's main.wxs has not one $anchor"
        }
    }
    $fragment = [System.IO.File]::ReadAllText((Join-Path $module "packaging\msi-path.xml"))
    $text = $text.Replace($filesReference, "$filesReference`n      <ComponentGroupRef Id=`"DogVisionCliPath`"/>")
    $text = $text.Replace("</Wix>", "$fragment</Wix>")
    [System.IO.File]::WriteAllText((Join-Path $resources "main.wxs"), $text)
}

# Each window's launcher has a shortcut in the Start menu, in a group of the package's name, and on
# the desktop, and dog-vision-cli.exe beside it has none; the command line's MSI has no shortcut at
# all, as package_msi_on_linux.sh says why.
if ($Window -eq "cli") {
    $launcherOptions = @("--win-console")
} else {
    $launcherOptions = @(
        "--add-launcher", "dog-vision-cli=$(Join-Path $packaging "dog-vision-cli.properties")",
        "--win-menu",
        "--win-menu-group", $name,
        "--win-shortcut"
    )
}

$output = Join-Path $outputDir $AppVersion
if (Test-Path $output) {
    Remove-Item -Recurse -Force $output
}


# jpackage, with package_msi_on_linux.sh's options.

$arguments = @(
    "--type", "msi",
    "--name", $name,
    "--app-version", $AppVersion,
    "--vendor", "Martin Lopatář",
    "--description", $description,
    "--license-file", (Join-Path $root "LICENSE"),
    "--icon", (Join-Path $packaging "dog-vision.ico"),
    "--input", $inputDir,
    "--main-jar", (Split-Path -Leaf $jar),
    "--main-class", $mainClass
) + $javaOptions + @(
    "--runtime-image", $runtime
) + $launcherOptions + @(
    "--resource-dir", $resources,
    "--win-dir-chooser",
    "--win-upgrade-uuid", $upgradeUuid,
    "--temp", (Join-Path $staging "temp"),
    "--dest", $output,
    # What light.exe says where it fails, which jpackage prints only then.
    "--verbose"
)

# From a file in UTF-8, which jpackage reads as its default charset: Java reads its command line in
# the system's ANSI code page, which on an English Windows, 1252, has no ř for the vendor's name.
# Each argument in quotes, inside which a backslash escapes the next character.
$argumentFile = Join-Path $staging "jpackage-arguments"
$quoted = $arguments | ForEach-Object { '"' + ($_ -replace '\\', '\\' -replace '"', '\"') + '"' }
[System.IO.File]::WriteAllLines($argumentFile, [string[]]$quoted, [System.Text.UTF8Encoding]::new($false))
Invoke-Checked (Join-Path $jdkBin "jpackage.exe") @("@$argumentFile")

Join-Path $output "$name-$AppVersion.msi"

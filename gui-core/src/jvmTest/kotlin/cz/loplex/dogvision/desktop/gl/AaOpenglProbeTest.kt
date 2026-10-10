package cz.loplex.dogvision.desktop.gl

import kotlin.test.Test

/** Probe: where the opengl32.dll this JVM has loaded comes from, before GlContextTest runs. */
class AaOpenglProbeTest {
    @Test
    fun printTheLoadedOpengl32() {
        val pid = ProcessHandle.current().pid()
        val script = "(Get-Process -Id $pid).Modules | Where-Object { \$_.ModuleName -match 'opengl32|gallium|libEGL|libGLESv2|d3d' } | " +
            "ForEach-Object { 'MODULE ' + \$_.FileName }"
        val process = ProcessBuilder("powershell", "-NoProfile", "-Command", script).redirectErrorStream(true).start()
        println("PROBE pid $pid\n" + process.inputStream.bufferedReader().readText())
        process.waitFor()
    }
}

package cz.loplex.inspectionfilter

import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.ide.IdeDistributionFactory
import com.intellij.ide.starter.ide.IdeInstaller
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.ide.InstalledIde
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.path.GlobalPaths
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.writeText

/** Misspelled words, each of which Typo reports in the project of [ideWithPlugin]. */
internal val TYPOS = listOf("wrold", "txet")

// Set by the integrationTest task in build.gradle.kts.
private val platformPath = Path(System.getProperty("path.to.platform"))
private val pluginPath = Path(System.getProperty("path.to.build.plugin"))
private val testsDir = Path(System.getProperty("ui.tests.dir"))

/**
 * A context for [testName] that starts the IDE Gradle has unpacked, with the plugin installed and a project of one text
 * file open, with the typos [TYPOS] in it for a run to report.
 */
internal fun ideWithPlugin(testName: String): IDETestContext {
    keepStarterUnder(testsDir)
    val projectDir = Files.createDirectories(testsDir.resolve("projects").resolve(testName))
    projectDir.resolve("a.txt").writeText("Some text, with ${TYPOS.joinToString(" and ")} in it.\n")
    val ide = IdeProductProvider.IU.copy(getInstaller = { InPlaceIdeInstaller(platformPath) })
    val context = Starter.newContext(testName, TestCase(ide, LocalProjectInfo(projectDir)))
    PluginConfigurator(context).installPluginFromPath(pluginPath)
    return context
}

/** Has Starter keep what it writes under [dir], rather than under out/ in the root of the repository. */
private fun keepStarterUnder(dir: Path) {
    di = DI {
        extend(di, allowOverride = true)
        bindSingleton<GlobalPaths>(overrides = true) { object : GlobalPaths(dir) {} }
    }
}

/**
 * Runs the IDE where it is, in [home]: the installer Starter has for an IDE already installed copies all of it, some
 * gigabytes, into its own cache first.
 */
private class InPlaceIdeInstaller(private val home: Path) : IdeInstaller {
    override suspend fun install(ideInfo: IdeInfo): Pair<String, InstalledIde> {
        val ide = di.direct.instance<IdeDistributionFactory>().installIDE(home.toFile(), ideInfo.executableFileName)
        return ide.build to ide
    }
}

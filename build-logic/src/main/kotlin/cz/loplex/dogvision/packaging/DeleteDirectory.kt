package cz.loplex.dogvision.packaging

import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

/** Deletes [directory], as a task's first action where the tool it runs wants it empty. */
class DeleteDirectory(private val directory: Provider<Directory>) : Action<Task> {
    override fun execute(task: Task) {
        directory.get().asFile.deleteRecursively()
    }
}

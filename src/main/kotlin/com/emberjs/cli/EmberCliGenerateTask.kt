package com.emberjs.cli

import com.intellij.ide.IdeView
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationType
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.RefreshQueue
import com.intellij.psi.PsiManager
import kotlin.text.Regex

class EmberCliGenerateTask(val module: Module, val template: String, val name: String, val view: IdeView?) :
        Task.Modal(module.project, "Generate Ember.js ${EmberCli.BLUEPRINTS[template]} '$name'", true) {

    private var notification: Notification? = null
    private val files = arrayListOf<VirtualFile>()

    private fun setNotification(content: String, type: NotificationType) {
        notification = NOTIFICATION_GROUP.createNotification("<b>Problem running ember-cli:</b><br/>$content", type)
    }

    override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true

        val moduleRoot = ModuleRootManager.getInstance(module)
        val workDir = moduleRoot.contentRoots.firstOrNull() ?:
                return setNotification("Could not determine working directory", NotificationType.ERROR)

        indicator.log("Preparing ember command ...")
        val cli = EmberCli("generate", template, name)
                .apply { workDirectory = workDir.path }

        indicator.log("Running ember generate $template $name ...")
        val output = try {
            cli.run()
        } catch (e: Exception) {
            return setNotification(e.message?.trim() ?: "Unknown exception occurred", NotificationType.ERROR)
        }

        indicator.log("Processing ember-cli output ...")
        // match "  creates some/file.js" lines
        val paths = output.lineSequence()
                .map { (CREATED_REGEX.find(it) ?: ROUTER_REGEX.matchEntire(it))?.groups?.get(1)?.value }
                .map { if (it == "router") "app/router.js" else it }
                .filterNotNull()
                .toList()

        indicator.log("ember-cli modified ${paths.size} files")
        // find file in virtual file system
        files.addAll(paths
                .map { LocalFileSystem.getInstance().refreshAndFindFileByPath("${workDir.path}/$it") }
                .filterNotNull())

        indicator.log("Refreshing ${files.size} modified files ...")
        RefreshQueue.getInstance().refresh(true, true, null, *files.toTypedArray())
    }

    override fun onSuccess() {
        notification?.notify(project)

        if (view != null) {
            val psiManager = PsiManager.getInstance(project)
            files.map { psiManager.findFile(it) }.filterNotNull().firstOrNull()?.apply {
                view.selectElement(this)
            }
        }
    }

    private fun ProgressIndicator.log(string: String) {
        text = string
    }

    companion object {
        private val CREATED_REGEX = Regex("  (?:create|overwrite)\\s+(.+)")
        private val ROUTER_REGEX = Regex("updating (router)")

        val NOTIFICATION_GROUP = NotificationGroup.balloonGroup("ember-cli")
    }
}

package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspacePreviewFileTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `preview resolution returns files inside the selected storage area`() {
        val manager = WorkspaceManager(tempFolder.newFolder("workspaces"))
        val workspace = "preview-test"
        manager.ensureWorkspace(workspace)
        val image = File(manager.filesDir(workspace), "images/photo.png").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

        assertEquals(
            image.canonicalFile,
            manager.resolvePreviewFile(workspace, WorkspaceStorageArea.FILES, "images/photo.png"),
        )
    }

    @Test
    fun `preview resolution rejects traversal directories and missing files`() {
        val manager = WorkspaceManager(tempFolder.newFolder("workspaces"))
        val workspace = "preview-test"
        manager.ensureWorkspace(workspace)
        File(manager.filesDir(workspace), "images").mkdirs()

        assertThrows(IllegalArgumentException::class.java) {
            manager.resolvePreviewFile(workspace, WorkspaceStorageArea.FILES, "../outside.png")
        }
        assertThrows(IllegalArgumentException::class.java) {
            manager.resolvePreviewFile(workspace, WorkspaceStorageArea.FILES, "images")
        }
        assertThrows(IllegalArgumentException::class.java) {
            manager.resolvePreviewFile(workspace, WorkspaceStorageArea.FILES, "missing.png")
        }
    }
}

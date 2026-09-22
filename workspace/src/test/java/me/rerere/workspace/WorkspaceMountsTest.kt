package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceMountsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val root = "test-workspace"

    private fun createManager(
        global: List<WorkspaceBindMount> = emptyList(),
        custom: List<WorkspaceBindMount> = emptyList(),
    ): WorkspaceManager = WorkspaceManager(
        baseDir = tempFolder.newFolder("workspaces"),
        bindMounts = global,
    ).also { it.ensureWorkspace(root) }

    @Test
    fun readOnlyMountUsesProotSpec() {
        val source = tempFolder.newFolder("readonly")
        val mount = WorkspaceBindMount(source = source, target = "/data/", readOnly = true)

        assertEquals("${source.absolutePath}:/data:ro", mount.prootBindSpec())
    }

    @Test
    fun customMountOverridesGlobalWithSameTarget() {
        val globalSource = tempFolder.newFolder("global")
        val customSource = tempFolder.newFolder("custom")
        val manager = createManager(
            global = listOf(WorkspaceBindMount(globalSource, "/data")),
        )
        val custom = listOf(WorkspaceBindMount(customSource, "/data"))

        assertEquals(
            listOf(customSource.absolutePath + ":/data"),
            manager.buildProotArgs(root, extraBindMounts = custom)
                .zipWithNext()
                .filter { (flag, _) -> flag == "-b" }
                .map { (_, value) -> value }
                .filter { it.endsWith(":/data") },
        )
    }

    @Test
    fun longerTargetIsMatchedFirst() {
        val parent = tempFolder.newFolder("parent")
        val child = tempFolder.newFolder("child")
        val manager = createManager(
            global = listOf(
                WorkspaceBindMount(parent, "/data"),
                WorkspaceBindMount(child, "/data/project"),
            ),
        )

        assertEquals(
            child,
            manager.resolveRootfsPath(root, "/data/project/file.txt").rootDir,
        )
    }

    @Test
    fun mountValidationReportsExpectedErrors() {
        val source = tempFolder.newFolder("source")
        val manager = WorkspaceManager(tempFolder.newFolder("workspaces"))

        assertNull(manager.validateMountDir(root, WorkspaceMountDir(source.path, "/data"), emptyList()))
        assertEquals(
            "target_reserved",
            manager.validateMountDir(root, WorkspaceMountDir(source.path, "/workspace/data"), emptyList()),
        )
        assertEquals(
            "source_missing",
            manager.validateMountDir(root, WorkspaceMountDir("/does/not/exist", "/data"), emptyList()),
        )
        assertEquals(
            "target_duplicated",
            manager.validateMountDir(
                root,
                WorkspaceMountDir(source.path, "/data"),
                listOf(WorkspaceMountDir(source.path, "/data/")),
            ),
        )
        assertEquals(
            "source_inside_rootfs",
            manager.validateMountDir(
                root,
                WorkspaceMountDir(File(manager.linuxDir(root), "inside").apply { mkdirs() }.path, "/data"),
                emptyList(),
            ),
        )
    }
}

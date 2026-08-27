package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.workspace.WorkspaceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceDetailVMTest {
    private fun file(name: String, path: String) = WorkspaceFileEntry(path, name, false, 1, 0)
    private fun dir(name: String, path: String) = WorkspaceFileEntry(path, name, true, 0, 0)

    @Test
    fun `expanded directory inlines cached children`() {
        val entries = listOf(dir("src", "src"), file("readme.md", "readme.md"))
        val cache = mapOf("src" to listOf(file("main.kt", "src/main.kt")))

        val rows = flattenWorkspaceTree(entries, setOf("src"), cache)

        assertEquals(
            listOf("src" to 0, "src/main.kt" to 1, "readme.md" to 0),
            rows.map { it.entry.path to it.depth },
        )
    }

    @Test
    fun `folder export plan always puts parents before children`() {
        val listing = mapOf(
            "notes" to listOf(dir("sub", "notes/sub"), file("a.txt", "notes/a.txt")),
            "notes/sub" to listOf(file("b.txt", "notes/sub/b.txt")),
        )

        val plan = planWorkspaceFolderExport("notes", listing)

        assertEquals(
            listOf("notes/sub", "notes/sub/b.txt", "notes/a.txt"),
            plan.map { it.sourcePath },
        )
    }
}

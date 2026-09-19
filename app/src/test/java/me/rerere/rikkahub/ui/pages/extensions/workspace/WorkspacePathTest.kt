package me.rerere.rikkahub.ui.pages.extensions.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspacePathTest {
    @Test
    fun parsesWorkspaceAndFileUriPaths() {
        assertEquals("notes/today.md", parseWorkspacePathReference("/workspace/notes/today.md")?.path)
        assertEquals("notes/today.md", parseWorkspacePathReference("file:///workspace/notes/today.md")?.path)
        assertEquals("notes/my file.md", parseWorkspacePathReference("FILE:///WORKSPACE/notes/my%20file.md")?.path)
    }

    @Test
    fun rejectsTraversalOutsideWorkspace() {
        assertNull(parseWorkspacePathReference("/workspace/../outside.txt"))
        assertNull(parseWorkspacePathReference("/workspace/a/../../outside.txt"))
    }

    @Test
    fun linkifiesPlainPathsButLeavesFencedCodeUntouched() {
        val result = linkifyWorkspacePaths(
            "Open /workspace/images/plot.png.\nSee file:///workspace/docs/readme.md\n\n\`\`\`text\n/workspace/raw.txt\n\`\`\`",
        )
        assertEquals(
            "Open [/workspace/images/plot.png](/workspace/images/plot.png).\nSee [file:///workspace/docs/readme.md](file:///workspace/docs/readme.md)\n\n\`\`\`text\n/workspace/raw.txt\n\`\`\`",
            result,
        )
    }
}

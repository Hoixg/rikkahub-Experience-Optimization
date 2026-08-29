package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.workspace.WorkspaceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceFileTypeTest {
    @Test
    fun `detects markdown and image file types`() {
        assertEquals(WorkspaceFileType.TEXT, entry("README.MD").detectFileType())
        assertEquals(WorkspaceFileType.IMAGE, entry("photo.JPEG").detectFileType())
    }

    @Test
    fun `resolves local markdown images against document directory`() {
        assertEquals(
            WorkspaceMarkdownImagePath.Local("docs/images/photo.png"),
            resolveWorkspaceMarkdownImagePath("docs/readme.md", "images/photo.png"),
        )
        assertEquals(
            WorkspaceMarkdownImagePath.Local("images/photo.png"),
            resolveWorkspaceMarkdownImagePath("docs/readme.md", "../images/photo.png"),
        )
        assertEquals(
            WorkspaceMarkdownImagePath.Local("images/photo.png"),
            resolveWorkspaceMarkdownImagePath("docs/readme.md", "/images/photo.png"),
        )
    }

    @Test
    fun `decodes local image paths without treating plus as a space`() {
        assertEquals(
            WorkspaceMarkdownImagePath.Local("docs/my image+1.png"),
            resolveWorkspaceMarkdownImagePath("docs/readme.md", "my%20image+1.png"),
        )
    }

    @Test
    fun `allows network images and rejects unsafe or unsupported sources`() {
        assertEquals(
            WorkspaceMarkdownImagePath.Network("https://example.com/image.png"),
            resolveWorkspaceMarkdownImagePath("docs/readme.md", "https://example.com/image.png"),
        )
        assertNull(resolveWorkspaceMarkdownImagePath("readme.md", "../outside.png"))
        assertNull(resolveWorkspaceMarkdownImagePath("readme.md", "file:///tmp/image.png"))
        assertNull(resolveWorkspaceMarkdownImagePath("readme.md", "content://images/1"))
        assertNull(resolveWorkspaceMarkdownImagePath("readme.md", "notes.txt"))
    }

    private fun entry(name: String) = WorkspaceFileEntry(name, name, false, 0, 0)
}

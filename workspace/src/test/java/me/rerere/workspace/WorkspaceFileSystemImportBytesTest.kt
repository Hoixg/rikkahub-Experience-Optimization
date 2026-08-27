package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class WorkspaceFileSystemImportBytesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `importBytes rejects a stream larger than the import cap`() {
        val root = tempFolder.newFolder("workspace")
        val fs = WorkspaceFileSystem(WorkspaceConfig(maxImportBytes = 10))

        assertThrows(IllegalArgumentException::class.java) {
            fs.importBytes(root, "upload.bin", ByteArrayInputStream(ByteArray(11)))
        }
    }

    @Test
    fun `importBytes deletes a partial file after exceeding the import cap`() {
        val root = tempFolder.newFolder("workspace")
        val fs = WorkspaceFileSystem(WorkspaceConfig(maxImportBytes = 10))

        assertThrows(IllegalArgumentException::class.java) {
            fs.importBytes(root, "upload.bin", ByteArrayInputStream(ByteArray(11)))
        }

        assertFalse(File(root, "upload.bin").exists())
    }

    @Test
    fun `importBytes uses an import cap independent of the model write cap`() {
        val root = tempFolder.newFolder("workspace")
        val fs = WorkspaceFileSystem(WorkspaceConfig(maxWriteBytes = 10, maxImportBytes = 100))

        val entry = fs.importBytes(root, "upload.bin", ByteArrayInputStream(ByteArray(11)))

        assertEquals(11L, entry.sizeBytes)
    }
}

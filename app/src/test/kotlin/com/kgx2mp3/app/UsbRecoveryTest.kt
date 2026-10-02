package com.kgx2mp3.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UsbRecoveryTest {
    private fun withRoot(test: (File, DestinationRoot) -> Unit) {
        val directory = Files.createTempDirectory("kgx-transaction-test").toFile()
        try { test(directory, FileRoot(directory)) } finally { directory.deleteRecursively() }
    }
    @Test fun interruptedPreparationRemovesOnlyTemporaryAndKeepsOriginal() = withRoot { dir, root ->
        File(dir, "song.mp3").writeText("original")
        File(dir, ".temp").writeText("partial")
        assertTrue(rollbackUsbFiles(root, "song.mp3", null, ".temp", ".backup", true))
        assertEquals("original", File(dir, "song.mp3").readText())
        assertFalse(File(dir, ".temp").exists())
    }
    @Test fun failedReplacementRestoresBackupAndRemovesPartialResult() = withRoot { dir, root ->
        File(dir, "song.mp3").writeText("broken new")
        File(dir, ".backup").writeText("old verified")
        File(dir, ".temp").writeText("new")
        assertTrue(rollbackUsbFiles(root, "song.mp3", "song.mp3", ".temp", ".backup", false))
        assertEquals("old verified", File(dir, "song.mp3").readText())
        assertFalse(File(dir, ".backup").exists())
        assertFalse(File(dir, ".temp").exists())
    }
    @Test fun failedInitialRenameDoesNotDeleteUnmovedOriginal() = withRoot { dir, root ->
        File(dir, "song.mp3").writeText("original")
        File(dir, ".temp").writeText("new")
        assertTrue(rollbackUsbFiles(root, "song.mp3", "song.mp3", ".temp", ".backup", false))
        assertEquals("original", File(dir, "song.mp3").readText())
    }
    @Test fun failedRollbackKeepsBackupForNextInsertion() = withRoot { dir, root ->
        File(dir, ".backup").writeText("old verified")
        val failedRenames = object : DestinationRoot {
            override fun create(name: String) = root.create(name)
            override fun find(name: String): DestinationFile? {
                val file = root.find(name) ?: return null
                return object : DestinationFile by file { override fun rename(name: String) = false }
            }
        }
        assertFalse(rollbackUsbFiles(failedRenames, "song.mp3", "song.mp3", ".temp", ".backup", false))
        assertEquals("old verified", File(dir, ".backup").readText())
    }
    @Test fun unavailableRootRaisesRatherThanPretendingRecoverySucceeded() = withRoot { dir, root ->
        dir.deleteRecursively()
        assertThrows(java.io.IOException::class.java) {
            rollbackUsbFiles(root, "song.mp3", "song.mp3", ".temp", ".backup", false)
        }
    }
}

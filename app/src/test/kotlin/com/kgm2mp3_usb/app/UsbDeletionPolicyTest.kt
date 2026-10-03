package com.kgm2mp3_usb.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class UsbDeletionPolicyTest {
    private fun withRoot(test: (File) -> Unit) {
        val root = Files.createTempDirectory("usb-delete-policy").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
    private fun song(file: File) = SongRef(file.absolutePath, file.name, path = file.absolutePath,
        size = file.length(), lastModified = file.lastModified())

    @Test fun acceptsOnlyUnchangedMusicInExactRoot() = withRoot { root ->
        val file = File(root, "song.mp3").apply { writeText("music") }
        assertEquals(file, UsbDeletionPolicy.requireDirectChild(root, song(file)))
        assertTrue(file.exists()) // Validation itself never deletes.
    }
    @Test fun rejectsPhoneAndNestedFiles() = withRoot { root ->
        val nested = File(root, "nested").apply { mkdir() }
        val file = File(nested, "song.mp3").apply { writeText("nested music") }
        assertThrows(IOException::class.java) { UsbDeletionPolicy.requireDirectChild(root, song(file)) }
        withRoot { phone ->
            val original = File(phone, "song.mp3").apply { writeText("phone music") }
            assertThrows(IOException::class.java) { UsbDeletionPolicy.requireDirectChild(root, song(original)) }
            assertEquals("phone music", original.readText())
        }
        assertEquals("nested music", file.readText())
    }
    @Test fun rejectsSymlinksEvenWhenTheyPointInsideRoot() = withRoot { root ->
        val original = File(root, "original.mp3").apply { writeText("music") }
        val link = File(root, "link.mp3")
        Files.createSymbolicLink(link.toPath(), original.toPath())
        assertThrows(IOException::class.java) { UsbDeletionPolicy.requireDirectChild(root, song(link)) }
        assertEquals("music", original.readText())
    }
    @Test fun rejectsSymlinkEscapeAndDirectories() = withRoot { root ->
        withRoot { phone ->
            val original = File(phone, "song.mp3").apply { writeText("phone music") }
            val link = File(root, "linked.mp3")
            Files.createSymbolicLink(link.toPath(), original.toPath())
            assertThrows(IOException::class.java) { UsbDeletionPolicy.requireDirectChild(root, song(link)) }
            assertEquals("phone music", original.readText())
        }
        val directory = File(root, "folder.mp3").apply { mkdir() }
        assertThrows(IOException::class.java) { UsbDeletionPolicy.requireDirectChild(root, song(directory)) }
    }
    @Test fun rejectsChangedNameLengthAndExactModifiedTime() = withRoot { root ->
        val file = File(root, "song.mp3").apply { writeText("music") }
        val selected = song(file)
        assertThrows(IOException::class.java) { UsbDeletionPolicy.requireDirectChild(root, selected.copy(name = "other.mp3")) }
        assertThrows(IOException::class.java) { UsbDeletionPolicy.requireDirectChild(root, selected.copy(size = selected.size + 1)) }
        assertThrows(IOException::class.java) { UsbDeletionPolicy.requireDirectChild(root, selected.copy(lastModified = 0)) }
        assertThrows(IOException::class.java) { UsbDeletionPolicy.requireDirectChild(root, selected.copy(lastModified = selected.lastModified + 1)) }
    }
    @Test fun rejectsUnsupportedAndTemporaryFiles() = withRoot { root ->
        for (name in listOf("readme.txt", "song.kgg", "song.mp3.part", ".kgx-song.mp3")) {
            val file = File(root, name).apply { writeText("data") }
            assertThrows(IOException::class.java) { UsbDeletionPolicy.requireDirectChild(root, song(file)) }
            assertTrue(file.exists())
        }
    }
}

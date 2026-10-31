package x.vladgba.files

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileOpsDropTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("drop").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun dir(path: String) = File(root, path).apply { mkdirs() }
    private fun file(path: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText("x") }

    @Test
    fun dropIntoAnotherFolderIsAllowed() {
        val a = file("left/a.txt")
        val b = dir("left/sub")
        assertEquals(FileOps.DropCheck.OK, FileOps.checkDrop(listOf(a, b), dir("right")))
    }

    @Test
    fun dropIntoSiblingFolderOfSameParentIsAllowed() {
        val a = file("left/a.txt")
        assertEquals(FileOps.DropCheck.OK, FileOps.checkDrop(listOf(a), dir("left/sub")))
    }

    @Test
    fun dropBackIntoOwnFolderDoesNothing() {
        val a = file("left/a.txt")
        val b = file("left/b.txt")
        assertEquals(FileOps.DropCheck.NOTHING_TO_DO, FileOps.checkDrop(listOf(a, b), File(root, "left")))
    }

    @Test
    fun ownFolderIsMatchedThroughDotDotPaths() {
        val a = file("left/a.txt")
        dir("other")
        assertEquals(FileOps.DropCheck.NOTHING_TO_DO, FileOps.checkDrop(listOf(a), File(root, "other/../left")))
    }

    @Test
    fun emptyDropDoesNothing() {
        assertEquals(FileOps.DropCheck.NOTHING_TO_DO, FileOps.checkDrop(emptyList(), root))
    }

    @Test
    fun folderIntoItselfOrItsChildIsRefused() {
        val folder = dir("left/folder")
        dir("left/folder/inner")
        assertEquals(FileOps.DropCheck.INTO_ITSELF, FileOps.checkDrop(listOf(folder), File(folder, "inner")))
    }
}

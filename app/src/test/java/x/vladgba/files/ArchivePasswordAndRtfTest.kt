package x.vladgba.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchivePasswordAndRtfTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun encryptedZipCanBeBrowsedAndExtractedWithCorrectPassword() {
        val source = tmp.newFolder("source")
        val nested = File(source, "folder/hello.txt").apply {
            parentFile.mkdirs()
            writeText("secret contents")
        }
        val archive = File(tmp.root, "protected.zip")
        val password = "correct horse".toCharArray()
        assertEquals(0, PasswordZipOps.create(listOf(nested), archive, password))
        assertTrue(PasswordZipOps.requiresPassword(archive))
        assertTrue(PasswordZipOps.validate(archive, password))
        assertFalse(PasswordZipOps.validate(archive, "wrong".toCharArray()))
        assertEquals("hello.txt", PasswordZipOps.readTree(archive).list("").single().name)
        val dest = File(tmp.root, "extracted")
        assertEquals(0, PasswordZipOps.extractAll(archive, dest, password))
        assertEquals("secret contents", File(dest, "hello.txt").readText())
    }

    @Test fun rtfPreviewRemovesMarkupAndHiddenObjects() {
        val rtf = "{\\rtf1\\ansi Hello \\b bold\\b0\\par Unicode: \\u8212? \\tab " +
            "\\'e9{\\fonttbl{\\f0 Arial;}}{\\*\\generator Hidden;} end}"
        assertEquals("Hello bold\nUnicode: — \té end", RtfOps.preview(rtf.toByteArray()))
    }

    @Test fun rtfTablesRemainReadableAndSelectableInBothLayouts() {
        val text = "| Name | Amount |\n|------+--------|\n| Item | 12.00 |\nNext paragraph"
        val reading = RtfOps.renderPage(text, false, false, 14f)
        assertTrue(reading.contains("<table>"))
        assertTrue(reading.contains("<td>Item</td><td>12.00</td>"))
        assertTrue(reading.contains("<p>Next paragraph</p>"))
        val original = RtfOps.renderPage(text, true, false, 14f)
        assertTrue(original.contains("<pre>| Name | Amount |"))
        assertFalse(original.contains("<table>"))
    }

    @Test fun rtfCellControlsCreateSeparateColumns() {
        val rtf = "{\\rtf1\\ansi\\trowd First\\cell Second\\cell\\row End}"
        assertEquals("First\tSecond\t\nEnd", RtfOps.preview(rtf.toByteArray()))
    }
}

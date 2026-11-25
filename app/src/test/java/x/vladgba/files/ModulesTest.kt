package x.vladgba.files

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModulesTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---------------------------------------------------------------- checksums

    @Test fun checksumsMatchKnownVectors() {
        val f = tmp.newFile("abc.txt").apply { writeText("abc") }
        val r = Checksums.compute(f)!!
        assertEquals("352441c2", r.crc32)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", r.md5)
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", r.sha1)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", r.sha256)
        assertEquals(r.sha256, Checksums.sha256(f))
    }

    @Test fun checksumsCanBeCancelled() {
        val f = tmp.newFile("x.bin").apply { writeBytes(ByteArray(1024)) }
        assertNull(Checksums.compute(f, cancelled = { true }))
    }

    // ---------------------------------------------------------------- tables

    @Test fun csvHandlesQuotesAndRaggedRows() {
        val t = TableOps.parseDelimited("name,note,n\r\n\"Doe, John\",\"said \"\"hi\"\"\nthen left\",1\nshort\n")
        assertEquals(listOf("name", "note", "n"), t.columns)
        assertEquals(2, t.rows.size)
        assertEquals(listOf("Doe, John", "said \"hi\"\nthen left", "1"), t.rows[0])
        assertEquals(listOf("short", "", ""), t.rows[1])
    }

    @Test fun delimiterIsDetected() {
        assertEquals(';', TableOps.detectDelimiter("a;b;c\n1;2;3\n"))
        assertEquals('\t', TableOps.detectDelimiter("a\tb\n1,5\t2\n"))
        assertEquals(',', TableOps.detectDelimiter("a,b\n\"x;y;z\",2\n"))
        assertEquals(listOf("a", "b"), TableOps.parseDelimited("a|b\n1|2\n").columns)
    }

    @Test fun csvRowLimitTruncates() {
        val text = "n\n" + (1..50).joinToString("\n")
        val t = TableOps.parseDelimited(text, maxRows = 10)
        assertTrue(t.truncated)
        assertEquals(10, t.rows.size)
    }

    @Test fun jsonArrayOfObjectsUnionsKeys() {
        val t = TableOps.parseJson("""[{"a":1,"b":"x"},{"b":null,"c":[1,2]}]""")
        assertEquals(listOf("a", "b", "c"), t.columns)
        assertEquals(listOf("1", "x", ""), t.rows[0])
        assertEquals(listOf("", "", "[1,2]"), t.rows[1])
    }

    @Test fun jsonWrappedArrayAndPlainObject() {
        assertEquals(listOf("id"), TableOps.parseJson("""{"total":2,"items":[{"id":1},{"id":2}]}""").columns)
        val kv = TableOps.parseJson("""{"x":1,"y":"two"}""")
        assertEquals(listOf("key", "value"), kv.columns)
        assertEquals(2, kv.rows.size)
        assertEquals(listOf("#1", "#2"), TableOps.parseJson("[[1,2],[3]]").columns)
        assertEquals(2, TableOps.parseJsonLines("{\"a\":1}\n\n{\"a\":2}\n").rows.size)
    }

    // ---------------------------------------------------------------- trash

    @Test fun trashMovesRestoresAndPurges() {
        val volume = tmp.newFolder("vol")
        val volumes = listOf(volume)
        val dir = File(volume, "docs").apply { mkdirs() }
        val f = File(dir, "a.txt").apply { writeText("one") }
        assertTrue(Trash.eligible(f, volumes))
        assertFalse(Trash.eligible(tmp.newFile("outside.txt"), volumes))

        assertTrue(Trash.moveToTrash(f, volumes, now = 1000L))
        assertFalse(f.exists())
        val items = Trash.list(volumes)
        assertEquals(1, items.size)
        assertEquals(f.absolutePath, items[0].original.absolutePath)
        assertEquals(1000L, items[0].deletedAt)
        assertFalse(Trash.eligible(items[0].payload, volumes)) // nothing is trashed twice

        // A new file took the name meanwhile: the restored one gets a numbered twin.
        f.writeText("two")
        val restored = Trash.restore(items[0])
        assertNotNull(restored)
        assertEquals("one", restored!!.readText())
        assertEquals("two", f.readText())
        assertTrue(Trash.list(volumes).isEmpty())

        val folder = File(volume, "old").apply { mkdirs(); File(this, "x").writeText("x") }
        assertTrue(Trash.moveToTrash(folder, volumes, now = 0L))
        assertTrue(Trash.moveToTrash(f, volumes, now = 40L * 24 * 3600 * 1000))
        assertEquals(1, Trash.purgeOld(volumes, now = 41L * 24 * 3600 * 1000))
        assertEquals(1, Trash.list(volumes).size)
        assertEquals(0, Trash.empty(volumes))
        assertTrue(Trash.list(volumes).isEmpty())
    }

    // ---------------------------------------------------------------- analyzer

    @Test fun analyzerSizesAndDuplicates() {
        val root = tmp.newFolder("scan")
        val big = ByteArray(200_000) { (it % 251).toByte() }
        File(root, "a").mkdirs()
        File(root, "b/deep").mkdirs()
        File(root, "a/big1.bin").writeBytes(big)
        File(root, "b/deep/big2.bin").writeBytes(big)
        // Same size and same first 64 KB, different tail: must not be reported.
        File(root, "b/almost.bin").writeBytes(big.copyOf().also { it[199_999] = 1 })
        File(root, "small1.txt").writeText("same")
        File(root, "a/small2.txt").writeText("same")
        File(root, "other.txt").writeText("diff")
        File(root, Trash.DIR + "/x").mkdirs()
        File(root, Trash.DIR + "/x/big3.bin").writeBytes(big)

        val scan = AnalyzeOps.scan(root)!!
        assertEquals(6, scan.fileCount)
        assertEquals(600_012L, scan.total)
        assertEquals("b", scan.children[0].file.name)
        assertEquals(400_000L, scan.children[0].size)
        assertEquals(2, scan.children[0].files)
        assertEquals(200_000L, scan.largest[0].length())

        val groups = AnalyzeOps.duplicates(scan)!!
        assertEquals(2, groups.size)
        assertEquals(listOf("big1.bin", "big2.bin"), groups[0].files.map { it.name })
        assertEquals(200_000L, groups[0].wasted)
        assertEquals(listOf("small2.txt", "small1.txt"), groups[1].files.map { it.name })
        assertNull(AnalyzeOps.scan(root, cancelled = { true }))
    }

    // ---------------------------------------------------------------- places

    @Test fun recentsKeepNewestFirstWithoutDuplicates() {
        var list = emptyList<String>()
        for (p in listOf("/a", "/b", "/a", "/c")) list = Places.pushed(list, p, 2)
        assertEquals(listOf("/c", "/a"), list)
    }

    // ---------------------------------------------------------------- 7z

    private fun sevenZ(name: String, password: CharArray? = null): File {
        val f = File(tmp.root, name)
        (if (password == null) SevenZOutputFile(f) else SevenZOutputFile(f, password)).use { out ->
            fun add(path: String, body: String?) {
                val e = out.createArchiveEntry(tmp.root, path)
                e.isDirectory = body == null
                e.setHasStream(body != null)
                out.putArchiveEntry(e)
                if (body != null) out.write(body.toByteArray())
                out.closeArchiveEntry()
            }
            add("docs/", null)
            add("docs/readme.txt", "hello 7z")
            add("docs/sub/deep.txt", "deep")
            add("top.txt", "top")
            add("../evil.txt", "nope")
        }
        return f
    }

    @Test fun sevenZTreeAndExtraction() {
        val arc = sevenZ("a.7z")
        assertTrue(ArchiveOps.isArchive(arc))
        assertFalse(ArchiveOps.needsPassword(arc))
        val tree = ArchiveOps.readTree(arc)
        assertEquals(3, tree.totalFiles)
        assertEquals(listOf("docs", "top.txt"), tree.list("").map { it.name })
        assertEquals(listOf("sub", "readme.txt"), tree.list("docs/").map { it.name })

        val one = File(tmp.root, "one.txt")
        ArchiveOps.extractEntry(arc, "docs/readme.txt", one)
        assertEquals("hello 7z", one.readText())

        val sub = tmp.newFolder("sub-out")
        assertEquals(0, ArchiveOps.extractSubtree(arc, "docs/", sub))
        assertEquals("deep", File(sub, "sub/deep.txt").readText())
        assertFalse(File(sub, "top.txt").exists())

        val all = tmp.newFolder("all-out")
        assertEquals(1, ArchiveOps.extractAll(arc, all)) // the entry that tries to escape is refused
        assertEquals("top", File(all, "top.txt").readText())
        assertFalse(File(tmp.root, "evil.txt").exists())
    }

    @Test fun sevenZPassword() {
        val arc = sevenZ("secret.7z", "pw".toCharArray())
        assertTrue(ArchiveOps.needsPassword(arc))
        val out = File(tmp.root, "dec.txt")
        ArchiveOps.extractEntry(arc, "top.txt", out, "pw".toCharArray())
        assertEquals("top", out.readText())
    }
}

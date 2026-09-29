package com.dshbox.app.sandbox.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class PackagesIndexTest {

    private val indexText = """
        Package: bash
        Version: 5.2.37-2+b10
        Essential: yes
        Priority: required
        Filename: pool/main/b/bash/bash_5.2.37-2+b10_arm64.deb
        SHA256: AAAA1111
        Size: 1234

        Package: git
        Version: 2.47.3
        Filename: pool/main/g/git/git_2.47.3_arm64.deb
        SHA256: bbbb2222
        Size: 4567
        Description: distributed version control system
         multi-line continuation
         more continuation

        Package: unwanted-pkg
        Version: 1.0
        Filename: pool/main/u/unwanted/unwanted_1.0_arm64.deb
        SHA256: cccc3333
        Size: 89
    """.trimIndent()

    @Test
    fun keepsOnlyWantedEntries() {
        val idx = PackagesIndex.parse(
            ByteArrayInputStream(indexText.toByteArray()),
            setOf("bash", "git", "absent"),
        )
        assertEquals(setOf("bash", "git"), idx.entries.keys)
        assertEquals("pool/main/b/bash/bash_5.2.37-2+b10_arm64.deb", idx.entry("bash")!!.filename)
        assertEquals("aaaa1111", idx.entry("bash")!!.sha256)
        assertEquals(1234L, idx.entry("bash")!!.sizeBytes)
        assertEquals("5.2.37-2+b10", idx.entry("bash")!!.version)
        assertEquals(4567L, idx.entry("git")!!.sizeBytes)
        assertNull(idx.entry("unwanted-pkg"))
        assertNull(idx.entry("absent"))
        assertEquals(listOf("absent"), idx.missingFrom(setOf("bash", "git", "absent")))
        assertEquals(3, idx.scannedParagraphs)
    }

    @Test
    fun missingRequiredFieldsThrow() {
        val bad = "Package: x\nFilename: pool/x.deb\n\n"
        try {
            PackagesIndex.parse(ByteArrayInputStream(bad.toByteArray()), setOf("x"))
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("Filename/SHA256"))
        }
    }

    @Test
    fun malformedLineThrows() {
        val bad = "not a field line\n"
        try {
            PackagesIndex.parse(ByteArrayInputStream(bad.toByteArray()), setOf("x"))
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("Malformed"))
        }
    }
}

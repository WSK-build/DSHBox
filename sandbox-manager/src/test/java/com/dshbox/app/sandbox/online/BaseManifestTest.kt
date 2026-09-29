package com.dshbox.app.sandbox.online

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class BaseManifestTest {

    @Test
    fun parsesScalarsAndSections() {
        val text = """
            # DSHBox base-layer manifest — generated; do not hand-edit
            manifest_version=1
            suite=trixie
            arch=arm64
            generated_at=2026-09-13

            [packages]
            base-files
            bash
            bash

            [smoke-binaries]
            /bin/bash
            /usr/bin/python3

            [locale-whitelist]
            C
            C.UTF-8
            zh_CN
        """.trimIndent()
        val m = BaseManifest.parse(ByteArrayInputStream(text.toByteArray()))
        assertEquals(1, m.manifestVersion)
        assertEquals("trixie", m.suite)
        assertEquals("arm64", m.arch)
        assertEquals("2026-09-13", m.generatedAt)
        assertEquals(listOf("base-files", "bash"), m.packages)
        assertEquals(listOf("/bin/bash", "/usr/bin/python3"), m.smokeBinaries)
        assertEquals(setOf("C", "C.UTF-8", "zh_CN"), m.localeWhitelist)
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val text = "manifest_version=99\nsuite=trixie\narch=arm64\n[packages]\nbase-files\n"
        try {
            BaseManifest.parse(ByteArrayInputStream(text.toByteArray()))
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertEquals("unsupported manifest version: 99", expected.message)
        }
    }

    @Test
    fun rejectsEmptyPackages() {
        val text = "manifest_version=1\nsuite=trixie\narch=arm64\n[packages]\n"
        try {
            BaseManifest.parse(ByteArrayInputStream(text.toByteArray()))
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertEquals("manifest has no packages", expected.message)
        }
    }

    @Test
    fun rejectsMalformedScalar() {
        val text = "not-a-scalar\n[packages]\nbase-files\n"
        try {
            BaseManifest.parse(ByteArrayInputStream(text.toByteArray()))
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            assertEquals("malformed manifest scalar line: not-a-scalar", expected.message)
        }
    }
}

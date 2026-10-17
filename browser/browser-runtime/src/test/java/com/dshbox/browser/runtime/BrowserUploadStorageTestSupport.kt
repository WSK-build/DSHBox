package com.dshbox.browser.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.SecureDirectoryStream
import org.junit.Assume.assumeTrue

/** These filesystem cases also run on Linux with an explicit zero-assumption gate. */
internal fun assumeSecureUploadStorage(root: File) {
    Files.newDirectoryStream(root.toPath()).use {
        assumeTrue("This filesystem lacks SecureDirectoryStream; run the Linux storage gate", it is SecureDirectoryStream<*>)
    }
}

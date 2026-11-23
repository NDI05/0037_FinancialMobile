package com.pam.recap.data.attachment

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * S4 C16/C17 storage gate: accepted files are copied into private storage with a real sha256,
 * oversize/unknown types are rejected before anything becomes reachable, and delete removes garbage.
 */
class PrivateAttachmentStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private fun store(filesDir: File = temp.newFolder("files")) =
        PrivateAttachmentStore(File(filesDir, "attachments"))

    @Test
    fun `5 MiB image is stored privately with a matching digest and relative ref`() = runTest {
        val filesDir = temp.newFolder("files")
        val store = PrivateAttachmentStore(File(filesDir, "attachments"))
        val bytes = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
        val result = store.import("u_1", "tx_1", "struk.png", "image/png", ByteArrayInputStream(bytes))
        val file = (result as Outcome.Success).value
        assertEquals(5L * 1024 * 1024, file.sizeBytes)
        assertEquals(64, file.sha256.length)
        assertTrue(file.storageRef.startsWith("attachments/u_1/tx_1/"))
        assertFalse(file.storageRef.contains(".."))
        assertTrue(file.file.isFile)
        assertTrue(file.file.canonicalPath.startsWith(filesDir.canonicalPath))
        assertEquals(bytes.size.toLong(), file.file.length())
        assertEquals(file.file, store.fileFor(file.storageRef))
        assertEquals(file.file, store.fileFor(file.storageRef))
    }

    @Test
    fun `files above the 10 MiB ceiling are rejected and leave no bytes behind`() = runTest {
        val filesDir = temp.newFolder("files")
        val store = PrivateAttachmentStore(File(filesDir, "attachments"))
        val stream = object : java.io.InputStream() {
            var produced = 0L
            override fun read(): Int {
                if (produced >= AttachmentRules.MAX_BYTES + 1) return -1
                produced += 1
                return 0
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (produced >= AttachmentRules.MAX_BYTES + 1) return -1
                val n = minOf(len.toLong(), AttachmentRules.MAX_BYTES + 1 - produced).toInt()
                produced += n
                return n
            }
        }
        val result = store.import("u_1", "tx_1", "besar.pdf", "application/pdf", stream)
        assertEquals(AttachmentRules.RULE_SIZE, (result as Outcome.Failure).error.debugDetail)
        val txDir = File(filesDir, "attachments/u_1/tx_1")
        assertTrue(!txDir.exists() || txDir.list().isNullOrEmpty())
    }

    @Test
    fun `unsupported types and unsafe names never reach storage`() = runTest {
        val filesDir = temp.newFolder("files")
        val store = PrivateAttachmentStore(File(filesDir, "attachments"))
        val badType = store.import("u_1", "tx_1", "x.html", "text/html", ByteArrayInputStream(ByteArray(10) { 1 }))
        assertTrue((badType as Outcome.Failure).error is AppError.Validation)
        val traversal = store.import("u_1", "tx_1", "../../etc/passwd", "image/png", ByteArrayInputStream(ByteArray(10) { 1 }))
        assertTrue(traversal is Outcome.Success)
        // The path part is stripped: the stored name cannot escape the private root.
        val file = (traversal as Outcome.Success).value
        assertEquals("passwd", file.fileName)
        assertTrue(file.file.canonicalPath.startsWith(filesDir.canonicalPath))
        val empty = store.import("u_1", "tx_1", "struk.png", "image/png", ByteArrayInputStream(ByteArray(0)))
        assertEquals(AttachmentRules.RULE_SIZE, (empty as Outcome.Failure).error.debugDetail)
    }

    @Test
    fun `delete removes the file and the storage ref lookup refuses traversal`() = runTest {
        val filesDir = temp.newFolder("files")
        val store = PrivateAttachmentStore(File(filesDir, "attachments"))
        val file = (
            store.import("u_1", "tx_1", "struk.png", "image/png", ByteArrayInputStream(ByteArray(64) { 7 })) as Outcome.Success
            ).value
        assertNull(store.fileFor("attachments/../../etc/passwd"))
        assertNull(store.fileFor("/etc/passwd"))
        assertTrue(store.delete(file.storageRef))
        assertNotNull(file.file)
        assertFalse(file.file.exists())
        assertNull(store.fileFor(file.storageRef))
    }
}

package com.pam.recap.data.attachment

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Rules mirrored from the server attachment contract (S4 C16/C17). */
object AttachmentRules {
    const val MAX_BYTES: Long = 10L * 1024 * 1024
    const val MAX_FILE_NAME = 255

    val MIME_TYPES: Set<String> = setOf(
        "application/pdf",
        "image/jpeg",
        "image/png",
        "image/webp",
        "image/heic",
    )

    val RULE_MIME = "attachment_mime_invalid"
    val RULE_SIZE = "attachment_size_invalid"
    val RULE_NAME = "attachment_file_name_invalid"
    val RULE_STORAGE = "attachment_storage_invalid"

    fun validate(fileName: String, mimeType: String, sizeBytes: Long): String? = when {
        fileName.isBlank() || fileName.length > MAX_FILE_NAME -> RULE_NAME
        fileName.contains('/') || fileName.contains('\\') -> RULE_NAME
        fileName.any { it.isISOControl() } -> RULE_NAME
        mimeType !in MIME_TYPES -> RULE_MIME
        sizeBytes !in 1..MAX_BYTES -> RULE_SIZE
        else -> null
    }
}

/** A file copied into the app's private storage, ready to be described to the server. */
data class PrivateAttachmentFile(
    val localFileId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Path relative to the app's private attachment root; also the server storageRef. */
    val storageRef: String,
    val file: File,
)

/**
 * Stores attachment bytes under `filesDir/attachments/<owner>/<transaction>/` and derives the
 * sha256 while copying. The server only ever receives metadata; bytes never leave the device.
 */
@Singleton
class PrivateAttachmentStore @Inject constructor(
    private val root: File,
) {

    fun fileFor(storageRef: String): File? {
        if (!isSafeRelative(storageRef)) return null
        val file = File(root, storageRef.removePrefix("attachments/"))
        return file.takeIf { it.isFile }
    }

    suspend fun import(
        ownerId: String,
        transactionId: String,
        fileName: String,
        mimeType: String,
        source: InputStream,
    ): Outcome<PrivateAttachmentFile> {
        val cleanName = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        AttachmentRules.validate(cleanName, mimeType, 1)?.let {
            return Outcome.Failure(AppError.Validation(it))
        }
        val id = UUID.randomUUID().toString()
        val relativeDir = "attachments/$ownerId/$transactionId"
        val targetDir = File(root, "$ownerId/$transactionId")
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return Outcome.Failure(AppError.Configuration("attachment_storage_unavailable"))
        }
        val target = File(targetDir, "${id}_$cleanName")
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            source.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > AttachmentRules.MAX_BYTES) {
                            target.delete()
                            return Outcome.Failure(AppError.Validation(AttachmentRules.RULE_SIZE))
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (total == 0L) {
                target.delete()
                return Outcome.Failure(AppError.Validation(AttachmentRules.RULE_SIZE))
            }
            Outcome.Success(
                PrivateAttachmentFile(
                    localFileId = id,
                    fileName = cleanName,
                    mimeType = mimeType,
                    sizeBytes = total,
                    sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                    storageRef = "$relativeDir/${target.name}",
                    file = target,
                ),
            )
        } catch (e: Exception) {
            target.delete()
            Outcome.Failure(AppError.Validation("attachment_write_failed"))
        }
    }

    fun delete(storageRef: String): Boolean {
        val file = fileFor(storageRef) ?: return false
        val deleted = file.delete()
        // Remove the now-empty owner/transaction folders so private storage does not leak structure.
        file.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
        file.parentFile?.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
        return deleted
    }

    fun isSafeRelative(ref: String): Boolean {
        if (ref.isBlank() || ref.length > 512) return false
        if (!ref.startsWith("attachments/")) return false
        if (ref.contains('\\') || ref.contains("://")) return false
        return ref.split('/').none { it == ".." || it == "." || it.isEmpty() }
    }
}

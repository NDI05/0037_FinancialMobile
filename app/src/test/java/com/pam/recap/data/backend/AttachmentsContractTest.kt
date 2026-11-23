package com.pam.recap.data.backend

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.util.PamJson
import com.pam.recap.domain.repository.AttachmentDraft
import com.pam.recap.domain.repository.AttachmentPatch
import com.pam.recap.domain.repository.BackendSessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S4 C16–C19 Android side of the attachment metadata contract: listing is owner+mime+q scoped,
 * writes carry the relative storage ref and sha256, and deletes use revision CAS.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentsContractTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: ServerFinanceRepositoryImpl

    private val sha = "a".repeat(64)

    @Before
    fun setUp() {
        server = MockWebServer(); server.start()
        val endpoints = object : BackendEndpoints {
            override val isEnabled = true
            override val analysesUrl: String? = server.url("/v1/analyses").toString()
            override fun endpoint(path: String) = server.url(path).toString()
        }
        val sessions = object : BackendSessionRepository {
            override val isEnabled = true
            override suspend fun register(email: String, password: String, displayName: String?, inviteCode: String) = Outcome.Success(Unit)
            override suspend fun login(email: String, password: String) = Outcome.Success(Unit)
            override suspend fun token(ownerId: String) = Outcome.Success("token")
            override suspend fun revoke() = Unit
            override fun clear() = Unit
        }
        val api = PamApiClient(OkHttpClient(), PamJson, endpoints, sessions, UnconfinedTestDispatcher())
        repo = ServerFinanceRepositoryImpl(api, endpoints)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `list sends scope filters and maps metadata without file bytes`() = runTest {
        server.enqueue(
            response(
                """{"items":[{"id":"att_1","transactionId":"tx_1","fileName":"struk.png","mimeType":"image/png",""" +
                    """"sizeBytes":2048,"sha256":"$sha","storageRef":"attachments/u_1/tx_1/struk.png",""" +
                    """"revision":4,"createdAt":"2026-11-20T03:04:05Z"}],"nextCursor":"cur_2"}""",
            ),
        )
        val page = (repo.attachments("u_1", "tx_1", "image/png", "struk", 25, "cur_1") as Outcome.Success).value
        val row = page.items.single()
        assertEquals("att_1", row.id)
        assertEquals(2048L, row.sizeBytes)
        assertEquals("attachments/u_1/tx_1/struk.png", row.storageRef)
        assertEquals(4, row.revision)
        assertEquals("cur_2", page.nextCursor)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        val url = request.requestUrl!!
        assertEquals("tx_1", url.queryParameter("transactionId"))
        assertEquals("image/png", url.queryParameter("mime"))
        assertEquals("struk", url.queryParameter("q"))
        assertEquals("25", url.queryParameter("limit"))
        assertEquals("cur_1", url.queryParameter("cursor"))
    }

    @Test
    fun `create posts the relative storage ref and normalizes the digest`() = runTest {
        server.enqueue(response(ATTACHMENT))
        val created = (
            repo.createAttachment(
                "u_1",
                AttachmentDraft(
                    transactionId = "tx_1",
                    fileName = "struk.png",
                    mimeType = "image/png",
                    sizeBytes = 2048,
                    sha256 = sha.uppercase(),
                    storageRef = "attachments/u_1/tx_1/struk.png",
                    localFileId = "file_9",
                    attributes = mapOf("sumber" to "kamera"),
                ),
            ) as Outcome.Success
            ).value
        assertEquals("att_1", created.id)
        assertEquals(sha, created.sha256)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"storageRef\":\"attachments/u_1/tx_1/struk.png\""))
        assertTrue(body.contains("\"sha256\":\"$sha\""))
        assertTrue(body.contains("\"localFileId\":\"file_9\""))
        assertTrue(body.contains("\"attributes\":{\"sumber\":\"kamera\"}"))
        assertTrue(!body.contains("bytes"))
    }

    @Test
    fun `invalid metadata is rejected before any request`() = runTest {
        val badSha = draft(sha256 = "xyz")
        assertTrue((repo.createAttachment("u_1", badSha) as Outcome.Failure).error is AppError.Validation)
        assertTrue((repo.createAttachment("u_1", draft(storageRef = "/absolute/struk.png")) as Outcome.Failure).error is AppError.Validation)
        assertTrue((repo.createAttachment("u_1", draft(storageRef = "../escape.png")) as Outcome.Failure).error is AppError.Validation)
        assertTrue((repo.createAttachment("u_1", draft(sizeBytes = 0)) as Outcome.Failure).error is AppError.Validation)
        assertTrue((repo.createAttachment("u_1", draft(mimeType = "text/html")) as Outcome.Failure).error is AppError.Validation)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `patch and delete carry revision and surface a conflict`() = runTest {
        server.enqueue(response(ATTACHMENT))
        val patched = (
            repo.patchAttachment("u_1", "att_1", AttachmentPatch(4, fileName = "struk-baru.png")) as Outcome.Success
            ).value
        assertEquals("att_1", patched.id)
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/v1/attachments/att_1", patch.path)
        assertTrue(patch.body.readUtf8().contains("\"revision\":4"))

        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"revision_conflict"}"""))
        val conflict = repo.deleteAttachment("u_1", "att_1", 4)
        assertTrue((conflict as Outcome.Failure).error is AppError.Conflict)
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("\"4\"", delete.getHeader("If-Match"))
    }

    private fun draft(
        sha256: String = sha,
        storageRef: String = "attachments/u_1/tx_1/struk.png",
        sizeBytes: Long = 2048,
        mimeType: String = "image/png",
    ) = AttachmentDraft(
        transactionId = "tx_1",
        fileName = "struk.png",
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        storageRef = storageRef,
    )

    private fun response(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        const val ATTACHMENT =
            """{"id":"att_1","transactionId":"tx_1","fileName":"struk.png","mimeType":"image/png",""" +
                """"sizeBytes":2048,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",""" +
                """"storageRef":"attachments/u_1/tx_1/struk.png","revision":4}"""
    }
}

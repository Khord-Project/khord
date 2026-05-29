package org.khord.shared.protocol.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.khord.shared.protocol.Base64Std
import org.khord.shared.protocol.ProtocolError
import org.khord.shared.protocol.wire.AcknowledgeRequest
import org.khord.shared.protocol.wire.CreateMailboxRequest
import org.khord.shared.protocol.wire.CreateMailboxResponse
import org.khord.shared.protocol.wire.FetchMessagesResponse
import org.khord.shared.protocol.wire.FetchedMessage
import org.khord.shared.protocol.wire.PowParamsResponse
import org.khord.shared.protocol.wire.SendMessageRequest
import org.khord.shared.protocol.wire.SendMessageResponse
import org.khord.shared.protocol.wire.UploadMediaResponse

/**
 * Stateless Relay Server REST client — PROTOCOL.md §5.
 *
 * The send and fetch APIs are intentionally split:
 *   * [sendMessage] is unauthenticated (knowing the mailbox ID is enough — §5.2);
 *   * [fetchMessages] / [acknowledge] require the bearer token returned at
 *     [createMailbox] time — only the mailbox owner can drain it.
 */
internal class RelayServerClient(
    private val http: HttpClient,
    private val baseUrl: String,
) {

    /** PROTOCOL.md §5.7 — public PoW parameters. */
    suspend fun powParams(): PowParamsResponse {
        val response: HttpResponse = http.get("$baseUrl/v1/pow-params")
        require(response.status.value == 200, response)
        return response.body()
    }

    /**
     * PROTOCOL.md §5.1 — create a mailbox. Returns the bearer token (43-char
     * base64url) which the caller MUST persist; it is returned exactly once.
     *
     * Throws [ProtocolError.MailboxConflict] on 409.
     */
    suspend fun createMailbox(mailboxId: String, proofOfWork: String): String {
        val response: HttpResponse = http.post("$baseUrl/v1/mailboxes") {
            contentType(ContentType.Application.Json)
            setBody(CreateMailboxRequest(mailboxId, proofOfWork))
        }
        if (response.status == HttpStatusCode.Conflict) throw ProtocolError.MailboxConflict()
        require(response.status.value in 200..299, response)
        return response.body<CreateMailboxResponse>().bearerToken
    }

    /**
     * PROTOCOL.md §5.2 — drop a blob into `mailboxId`. NO auth.
     *
     * `blob` is the raw bytes of the Khord wire envelope; this method
     * base64-encodes it for the server.
     */
    suspend fun sendMessage(mailboxId: String, blob: ByteArray): Long {
        val response: HttpResponse = http.post("$baseUrl/v1/mailboxes/$mailboxId/messages") {
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(blob = Base64Std.encode(blob)))
        }
        if (response.status == HttpStatusCode.NotFound) {
            throw ProtocolError.HttpError(404, "mailbox not found")
        }
        require(response.status.value in 200..299, response)
        return response.body<SendMessageResponse>().sequence
    }

    /** PROTOCOL.md §5.3 — bearer-authed fetch of unacked messages. */
    suspend fun fetchMessages(
        mailboxId: String,
        bearerToken: String,
        afterSequence: Long = 0,
    ): List<FetchedMessage> {
        val response: HttpResponse = http.get("$baseUrl/v1/mailboxes/$mailboxId/messages") {
            bearerAuth(bearerToken)
            parameter("after_sequence", afterSequence)
        }
        if (response.status == HttpStatusCode.Unauthorized) throw ProtocolError.AuthFailed()
        require(response.status.value == 200, response)
        return response.body<FetchMessagesResponse>().messages
    }

    /**
     * ADR 029 — upload an encrypted media blob. NO auth (proof-of-work
     * only); the PoW nonce must solve SHA-256(sha256_hex(blob) || nonce),
     * mined by the caller. Returns the relay-assigned media id.
     *
     * Sent as multipart/form-data with a binary `file` part (so the 10 MiB
     * cap isn't inflated 33% by base64) + a `proof_of_work` form field. The
     * `file` part carries a filename so FastAPI treats it as an upload.
     */
    suspend fun uploadMedia(encryptedBlob: ByteArray, proofOfWork: String): String {
        val response: HttpResponse = http.post("$baseUrl/v1/media") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("proof_of_work", proofOfWork)
                        append(
                            key = "file",
                            value = encryptedBlob,
                            headers = io.ktor.http.Headers.build {
                                append(HttpHeaders.ContentType, "application/octet-stream")
                                append(HttpHeaders.ContentDisposition, "filename=\"blob\"")
                            },
                        )
                    },
                ),
            )
        }
        if (response.status.value == 413) {
            throw ProtocolError.HttpError(413, "media too large")
        }
        require(response.status.value in 200..299, response)
        return response.body<UploadMediaResponse>().mediaId
    }

    /**
     * ADR 029 — fetch an encrypted media blob by id. NO auth (the random id
     * is the capability). One-time read: the relay deletes the blob on the
     * first successful fetch, so a 404 here means "already consumed, gone,
     * or expired". Returns the raw encrypted bytes for the caller to decrypt.
     */
    suspend fun downloadMedia(mediaId: String): ByteArray {
        val response: HttpResponse = http.get("$baseUrl/v1/media/$mediaId")
        if (response.status == HttpStatusCode.NotFound) {
            throw ProtocolError.HttpError(404, "media not found or already fetched")
        }
        require(response.status.value == 200, response)
        return response.readRawBytes()
    }

    /** PROTOCOL.md §5.4 — bearer-authed delete of acknowledged messages. */
    suspend fun acknowledge(mailboxId: String, bearerToken: String, throughSequence: Long) {
        val response: HttpResponse = http.post("$baseUrl/v1/mailboxes/$mailboxId/ack") {
            bearerAuth(bearerToken)
            contentType(ContentType.Application.Json)
            setBody(AcknowledgeRequest(throughSequence = throughSequence))
        }
        if (response.status == HttpStatusCode.Unauthorized) throw ProtocolError.AuthFailed()
        require(response.status.value in 200..299, response)
    }
}

private suspend fun require(predicate: Boolean, response: HttpResponse) {
    if (!predicate) {
        throw ProtocolError.HttpError(response.status.value, response.bodyAsText())
    }
}

package org.khord.shared.protocol.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.khord.shared.protocol.Base64Std
import org.khord.shared.protocol.ProtocolError
import org.khord.shared.protocol.wire.BundleFetchResponse
import org.khord.shared.protocol.wire.BundleUploadRequest
import org.khord.shared.protocol.wire.ChallengeResponse
import org.khord.shared.protocol.wire.VerifyRequest
import org.khord.shared.protocol.wire.VerifyResponse

/**
 * Stateless Key Server REST client — PROTOCOL.md §4.
 *
 * Stateless because all auth state (challenge bytes, session token) is
 * passed in by the caller. Token caching, re-auth-on-401, and bundle
 * lifecycle decisions live in the orchestrator (see [Messaging]).
 */
internal class KeyServerClient(
    private val http: HttpClient,
    private val baseUrl: String,
) {

    /** PROTOCOL.md §4.4 step 1. */
    suspend fun requestChallenge(fingerprint: String): ByteArray {
        val response: HttpResponse = http.get("$baseUrl/v1/auth/challenge/$fingerprint")
        require(response.status.value == 200, response)
        val body: ChallengeResponse = response.body()
        return Base64Std.decode(body.challenge)
    }

    /**
     * PROTOCOL.md §4.4 step 2.
     *
     * Pass `identityPublicKey` on every call (server ignores it on returning
     * auth, validates it on first-time auth). This avoids first-time vs
     * returning state bleeding into the client.
     *
     * Returns the bearer token (15-minute TTL by default).
     */
    suspend fun verifyChallenge(
        fingerprint: String,
        challenge: ByteArray,
        signature: ByteArray,
        identityPublicKey: ByteArray,
    ): String {
        val response: HttpResponse = http.post("$baseUrl/v1/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(
                VerifyRequest(
                    fingerprint = fingerprint,
                    challenge = Base64Std.encode(challenge),
                    signature = Base64Std.encode(signature),
                    identityKey = Base64Std.encode(identityPublicKey),
                )
            )
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw ProtocolError.AuthFailed()
        }
        require(response.status.value == 200, response)
        return response.body<VerifyResponse>().token
    }

    /** PROTOCOL.md §4.1 — bearer-authed bundle upload. */
    suspend fun uploadBundle(
        fingerprint: String,
        bundle: BundleUploadRequest,
        token: String,
    ) {
        val response: HttpResponse = http.post("$baseUrl/v1/keys/$fingerprint/bundle") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(bundle)
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw ProtocolError.AuthFailed("token rejected by Key Server")
        }
        require(response.status.value in 200..299, response)
    }

    /** PROTOCOL.md §4.2 — public bundle fetch (consumes one OPK on the server). */
    suspend fun fetchBundle(fingerprint: String): BundleFetchResponse {
        val response: HttpResponse = http.get("$baseUrl/v1/keys/$fingerprint/bundle")
        if (response.status == HttpStatusCode.NotFound) {
            throw ProtocolError.HttpError(404, "fingerprint not registered")
        }
        require(response.status.value == 200, response)
        return response.body()
    }
}

private suspend fun require(predicate: Boolean, response: HttpResponse) {
    if (!predicate) {
        throw ProtocolError.HttpError(response.status.value, response.bodyAsText())
    }
}

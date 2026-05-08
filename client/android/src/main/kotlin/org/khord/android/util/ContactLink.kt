package org.khord.android.util

import java.util.Base64

/**
 * Encode/decode the `khord://contact/<base64url-encoded JSON>` URI form
 * of a QrPayload, used by the "Copy contact link" / "Share" buttons and
 * by the "Enter contact link manually" input field.
 *
 * The visible QR code itself still encodes raw JSON — keeping camera
 * scans and the wire format untouched. This URI is purely a copy/share
 * convenience, plus a future-ready hook for `intent-filter` deep links.
 *
 * The parser accepts three formats so the same input field works whether
 * the user pasted a URI, a bare base64 blob, or raw JSON copied from a
 * debug tool:
 *
 *   1. `khord://contact/<base64url>` — strip prefix, base64-decode
 *   2. raw `<base64url>`             — base64-decode
 *   3. raw JSON (starts with `{`)    — pass through
 *
 * `toJson` returns whatever JSON it produced; downstream callers
 * (`AddContactViewModel.onScanned`) validate the JSON shape, so this
 * helper deliberately doesn't depend on kotlinx-serialization.
 */
object ContactLink {

    private const val URI_PREFIX = "khord://contact/"
    private val URL_ENCODER = Base64.getUrlEncoder().withoutPadding()
    private val URL_DECODER = Base64.getUrlDecoder()

    /** Build the `khord://contact/<base64url>` link from a raw JSON payload. */
    fun encode(json: String): String {
        val b64 = URL_ENCODER.encodeToString(json.toByteArray(Charsets.UTF_8))
        return URI_PREFIX + b64
    }

    /**
     * Parse user input in any supported form and return the underlying
     * JSON string. Throws [IllegalArgumentException] on unrecoverable input
     * (bad base64, non-UTF-8 bytes, empty after trimming).
     *
     * JSON well-formedness is NOT checked here — the caller's existing
     * QrPayload parser handles that.
     */
    fun toJson(input: String): String {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "empty contact link" }

        if (trimmed.startsWith("{")) return trimmed

        val b64 = if (trimmed.startsWith(URI_PREFIX, ignoreCase = true)) {
            trimmed.substring(URI_PREFIX.length)
        } else {
            trimmed
        }
        require(b64.isNotEmpty()) { "no payload after prefix" }
        val bytes = try {
            URL_DECODER.decode(b64)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("not valid base64url", e)
        }
        return String(bytes, Charsets.UTF_8)
    }
}

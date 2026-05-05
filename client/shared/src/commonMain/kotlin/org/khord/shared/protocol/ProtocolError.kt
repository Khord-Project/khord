package org.khord.shared.protocol

/**
 * Sealed protocol-layer error hierarchy. All thrown by the server clients
 * and the messaging orchestrator; callers can pattern-match.
 */
sealed class ProtocolError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Server returned a non-2xx status that isn't otherwise structured. */
    class HttpError(val status: Int, message: String) :
        ProtocolError("HTTP $status: $message")

    /** Bearer / session token rejected. */
    class AuthFailed(message: String = "auth failed") : ProtocolError(message)

    /** Mailbox already exists (PROTOCOL.md §5.1 — 409). */
    class MailboxConflict(message: String = "mailbox already exists") : ProtocolError(message)

    /** Wire envelope unparseable or wrong shape for context. */
    class WireFormatError(message: String, cause: Throwable? = null) : ProtocolError(message, cause)

    /** SPK signature on a fetched bundle did not verify (X3DH §4.5). */
    class BadSignedPreKey(message: String = "signed pre-key signature failed") : ProtocolError(message)

    /** Inner payload type was not understood — caller should display "unsupported message type". */
    class UnsupportedPayloadType(val payloadType: String) :
        ProtocolError("unsupported payload type: $payloadType")
}

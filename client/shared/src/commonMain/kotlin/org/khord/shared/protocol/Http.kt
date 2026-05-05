package org.khord.shared.protocol

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * Construct a Khord-configured Ktor HTTP client around `engineFactory`.
 *
 * Engine-as-parameter pattern: callers pick the engine (`Java`, `OkHttp`,
 * `Darwin`, `Js`) according to their target. The shared module therefore
 * has no per-target `actual` declarations for the HTTP layer — only a
 * single `commonMain` factory that takes the engine as input.
 */
internal fun khordHttpClient(engineFactory: HttpClientEngineFactory<*>): HttpClient =
    HttpClient(engineFactory) {
        expectSuccess = false  // we read status codes explicitly per endpoint
        install(ContentNegotiation) {
            json(KhordJson)
        }
    }

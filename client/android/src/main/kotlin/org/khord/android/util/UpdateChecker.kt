package org.khord.android.util

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.khord.android.BuildConfig

/**
 * Result of a successful update check — fed to the UI banner.
 */
data class UpdateInfo(
    /** New version, with the leading `v` stripped (e.g. "0.1.1-alpha"). */
    val version: String,
    /** Browser URL of the GitHub Release page. */
    val htmlUrl: String,
)

/**
 * Lightweight, fire-once-per-cold-start GitHub Releases polling.
 *
 *   - One `GET` to /releases/latest. No retries on failure.
 *   - 5 s timeout — if the network is slow we just skip the check this
 *     session.
 *   - Compares the tag (minus the leading `v`) to [BuildConfig.VERSION_NAME].
 *     Returns [UpdateInfo] iff the remote tag is strictly newer.
 *   - Any HTTP error, parse error, or timeout returns null — silently.
 *     The UI banner is opt-in attention; an inability to check should
 *     never block, log a complaint, or surface anything to the user.
 *
 * Note: `/releases/latest` excludes prereleases by default. The
 * current Khord PoC ships as v*-alpha prereleases, so this endpoint
 * returns 404 (no non-prerelease exists yet) and we get null. To
 * surface alpha-to-alpha updates we'd swap to `/releases` and pick
 * the first by date. Doing that pivot when v0.1.0 (non-prerelease)
 * ships — for now this is a stub that activates on first stable
 * release.
 *
 * Privacy note: this is a single anonymous GET to api.github.com.
 * No telemetry, no install id, no auth header — the only signal
 * the request carries is the user-agent string and the source IP.
 * Matches the project's no-tracking stance.
 */
object UpdateChecker {

    private const val RELEASES_LATEST_URL =
        "https://api.github.com/repos/Khord-Project/khord/releases/latest"

    private const val TIMEOUT_MS = 5_000L

    // Parsed manually via JsonElement APIs (the Android module doesn't
    // have the kotlinx-serialization compiler plugin applied — only
    // :shared does — so we can't use @Serializable here without a
    // build.gradle change). The two fields we need are tag_name and
    // html_url; everything else in the ~30-field response is ignored.
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Run a single GitHub Releases check. Returns the new-version info
     * if (and only if) a strictly-newer release is published. Never
     * throws — every failure path returns null.
     */
    suspend fun checkOnce(http: HttpClient): UpdateInfo? = runCatching {
        withTimeoutOrNull(TIMEOUT_MS) {
            val response: HttpResponse = http.get(RELEASES_LATEST_URL)
            if (response.status != HttpStatusCode.OK) return@withTimeoutOrNull null
            val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val tagName = obj["tag_name"]?.jsonPrimitive?.content ?: return@withTimeoutOrNull null
            val htmlUrl = obj["html_url"]?.jsonPrimitive?.content ?: return@withTimeoutOrNull null
            val latestVersion = tagName.removePrefix("v")
            if (isNewer(remote = latestVersion, local = BuildConfig.VERSION_NAME)) {
                UpdateInfo(version = latestVersion, htmlUrl = htmlUrl)
            } else null
        }
    }.getOrNull()

    /**
     * Compare two semver-ish version strings. Returns true iff [remote]
     * is strictly newer than [local].
     *
     * Rules (good enough for Khord's `MAJOR.MINOR.PATCH[-PRE]` tagging
     * convention — full semver build-metadata is intentionally out of
     * scope for this PoC checker):
     *   1. Split numeric prefix and optional `-prerelease` suffix.
     *   2. Compare numeric components left-to-right, padding with 0.
     *   3. If numeric parts equal: a non-empty prerelease loses to an
     *      empty one ("1.0.0-alpha" < "1.0.0"); two non-empties compare
     *      lexicographically ("1.0.0-alpha" < "1.0.0-beta").
     *
     * Known limitation: `1.0.0-alpha.10` < `1.0.0-alpha.2` lexically
     * (wrong). The Khord tagging scheme uses single-word prereleases
     * (`alpha`, `beta`) so this doesn't matter in practice. If we ever
     * adopt numbered prereleases this comparator gets a real semver
     * library.
     */
    internal fun isNewer(remote: String, local: String): Boolean {
        if (remote == local) return false
        val (remoteNums, remotePre) = parseVersion(remote)
        val (localNums, localPre) = parseVersion(local)
        val maxLen = maxOf(remoteNums.size, localNums.size)
        for (i in 0 until maxLen) {
            val r = remoteNums.getOrElse(i) { 0 }
            val l = localNums.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        // Numeric parts equal — compare prerelease tags.
        return when {
            remotePre.isEmpty() && localPre.isNotEmpty() -> true   // 1.0.0 > 1.0.0-alpha
            remotePre.isNotEmpty() && localPre.isEmpty() -> false  // 1.0.0-alpha < 1.0.0
            else -> remotePre > localPre                            // lex order
        }
    }

    private fun parseVersion(v: String): Pair<List<Int>, String> {
        val dash = v.indexOf('-')
        val numericPart = if (dash >= 0) v.substring(0, dash) else v
        val prePart = if (dash >= 0) v.substring(dash + 1) else ""
        val nums = numericPart.split('.').mapNotNull { it.toIntOrNull() }
        return nums to prePart
    }
}

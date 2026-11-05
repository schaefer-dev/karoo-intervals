package com.karoo_intervals.intervals

import java.time.LocalDate
import java.util.Base64

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
)

/** [status] 0 means no HTTP response (network/transport error, see [error]). */
data class HttpResult(val status: Int, val body: String?, val error: String? = null)

/** Raw bodies of one successful sync; parsed lazily by the repository. */
data class SyncPayload(val sportSettingsJson: String, val eventsJson: String)

sealed interface SyncOutcome {
    data class Ok(val payload: SyncPayload) : SyncOutcome
    data class Failed(val reason: String) : SyncOutcome
}

/** intervals.icu REST requests. Pure (HTTP is injected) so it stays JVM-testable. */
object IntervalsApi {
    private const val BASE = "https://intervals.icu/api/v1"
    const val DAYS_BACK = 1L
    const val DAYS_AHEAD = 7L

    fun headers(apiKey: String): Map<String, String> = mapOf(
        // Personal API key: Basic auth with username "API_KEY".
        "Authorization" to "Basic " + Base64.getEncoder()
            .encodeToString("API_KEY:$apiKey".toByteArray(Charsets.UTF_8)),
        "Accept" to "application/json",
        "User-Agent" to "karoo-intervals-screen",
    )

    fun sportSettingsRequest(apiKey: String) = HttpRequest(
        method = "GET",
        url = "$BASE/athlete/0/sport-settings/Ride",
        headers = headers(apiKey),
    )

    fun eventsRequest(apiKey: String, today: LocalDate) = HttpRequest(
        method = "GET",
        url = "$BASE/athlete/0/events?oldest=${today.minusDays(DAYS_BACK)}" +
            "&newest=${today.plusDays(DAYS_AHEAD)}&category=WORKOUT",
        headers = headers(apiKey),
    )

    suspend fun sync(
        apiKey: String,
        today: LocalDate,
        http: suspend (HttpRequest) -> HttpResult,
    ): SyncOutcome {
        if (apiKey.isBlank()) return SyncOutcome.Failed("No intervals.icu API key")
        val settings = http(sportSettingsRequest(apiKey))
        settings.failure()?.let { return SyncOutcome.Failed(it) }
        val events = http(eventsRequest(apiKey, today))
        events.failure()?.let { return SyncOutcome.Failed(it) }
        return SyncOutcome.Ok(SyncPayload(settings.body!!, events.body!!))
    }

    private fun HttpResult.failure(): String? = when {
        status == 0 -> error ?: "No connection"
        status == 401 || status == 403 -> "API key rejected ($status)"
        status !in 200..299 -> "HTTP $status"
        body == null -> "Empty response"
        else -> null
    }
}

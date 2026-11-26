package com.jacobcraig.stridehearth.weather

import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The only outbound HTTP call the Server can make (ADR-0017): the Open-Meteo ERA5 archive lookup, built only when an
 * Operator sets `stridehearth.weather.provider=open-meteo`. What it sends: the Activity's start point rounded to
 * 0.1 degrees (about 11 km) and the UTC date of its start. Never the route, the athlete or the Activity.
 * The start hour's values are picked from the day's answer here.
 */
class OpenMeteoClient(
    baseUrl: String,
    timeout: Duration,
) {
    private val client: RestClient =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(timeout)
                    setReadTimeout(timeout)
                },
            ).build()

    /**
     * The weather at [startedAt]'s hour at ([latitude], [longitude]), with null values for what the archive has none
     * of. Throws when the provider is down, slow or rate-limited, or answers something unusable.
     */
    fun lookup(
        latitude: Double,
        longitude: Double,
        startedAt: Instant,
    ): WeatherObservation {
        val utc = startedAt.atZone(ZoneOffset.UTC)
        val date = utc.toLocalDate().toString()
        val body =
            client
                .get()
                .uri { uri ->
                    uri
                        .path("/v1/archive")
                        .queryParam("latitude", round(latitude))
                        .queryParam("longitude", round(longitude))
                        .queryParam("start_date", date)
                        .queryParam("end_date", date)
                        .queryParam("hourly", "temperature_2m,dew_point_2m,relative_humidity_2m,wind_speed_10m")
                        .queryParam("wind_speed_unit", "ms")
                        .queryParam("timezone", "GMT")
                        .build()
                }.retrieve()
                .body(JsonNode::class.java) ?: error("Open-Meteo returned no body")
        val hourly = body.path("hourly")
        val hour = utc.truncatedTo(ChronoUnit.HOURS).format(HOUR_FORMAT)
        val index = hourly.path("time").indexOfFirst { it.asString() == hour }
        check(index >= 0) { "Open-Meteo's answer has no hour $hour" }
        return WeatherObservation(
            temperatureC = hourly.value("temperature_2m", index),
            dewPointC = hourly.value("dew_point_2m", index),
            humidityPct = hourly.value("relative_humidity_2m", index),
            windSpeedMs = hourly.value("wind_speed_10m", index),
        )
    }

    private fun JsonNode.value(
        name: String,
        index: Int,
    ): Double? = path(name).path(index).takeIf { it.isNumber }?.asDouble()

    companion object {
        private val HOUR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

        /** [degrees] rounded to 0.1, as the text that is sent. */
        fun round(degrees: Double): String = BigDecimal.valueOf(degrees).setScale(1, RoundingMode.HALF_UP).toPlainString()
    }
}

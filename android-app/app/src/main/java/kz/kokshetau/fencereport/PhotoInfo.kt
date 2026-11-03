package kz.kokshetau.fencereport

import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Данные о месте и времени съёмки. */
data class PhotoInfo(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val altitude: Double?,
    val timeMillis: Long,
) {
    /** Ссылка на точку в 2ГИС (порядок в 2ГИС: долгота, широта). */
    val twoGisLink: String
        get() {
            val lon = "%.6f".format(Locale.US, longitude)
            val lat = "%.6f".format(Locale.US, latitude)
            return "https://2gis.kz/kokshetau/geo/$lon,$lat?m=$lon%2C$lat%2F18"
        }

    val coordinatesText: String
        get() {
            val base = "%.6f, %.6f".format(Locale.US, latitude, longitude)
            return if (accuracyMeters != null) "$base  (±${accuracyMeters.toInt()} м)" else base
        }

    val dateText: String
        get() = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(Date(timeMillis))

    fun putInto(intent: Intent) {
        intent.putExtra(EXTRA_LAT, latitude)
        intent.putExtra(EXTRA_LON, longitude)
        accuracyMeters?.let { intent.putExtra(EXTRA_ACC, it) }
        altitude?.let { intent.putExtra(EXTRA_ALT, it) }
        intent.putExtra(EXTRA_TIME, timeMillis)
    }

    companion object {
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"
        private const val EXTRA_ACC = "acc"
        private const val EXTRA_ALT = "alt"
        private const val EXTRA_TIME = "time"

        fun from(intent: Intent): PhotoInfo? {
            if (!intent.hasExtra(EXTRA_LAT) || !intent.hasExtra(EXTRA_LON)) return null
            return PhotoInfo(
                latitude = intent.getDoubleExtra(EXTRA_LAT, 0.0),
                longitude = intent.getDoubleExtra(EXTRA_LON, 0.0),
                accuracyMeters = if (intent.hasExtra(EXTRA_ACC)) intent.getFloatExtra(EXTRA_ACC, 0f) else null,
                altitude = if (intent.hasExtra(EXTRA_ALT)) intent.getDoubleExtra(EXTRA_ALT, 0.0) else null,
                timeMillis = intent.getLongExtra(EXTRA_TIME, System.currentTimeMillis()),
            )
        }
    }
}

package kz.kokshetau.fencereport

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Сохраняет готовое фото в галерею с GPS и ссылкой 2ГИС в метаданных (EXIF). */
object ReportSaver {

    const val ALBUM = "FenceReports"

    fun save(context: Context, bitmap: Bitmap, info: PhotoInfo, markerCount: Int): Uri {
        val tmp = File(context.cacheDir, "report_out.jpg")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        writeExif(tmp, info, markerCount)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(info.timeMillis))
        val name = "Zabor_$stamp.jpg"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveMediaStore(context, tmp, name, info)
            } else {
                saveLegacy(context, tmp, name)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun writeExif(file: File, info: PhotoInfo, markerCount: Int) {
        val exif = ExifInterface(file.absolutePath)
        val date = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(info.timeMillis))
        exif.setLatLong(info.latitude, info.longitude)
        info.altitude?.let { exif.setAltitude(it) }
        exif.setAttribute(ExifInterface.TAG_DATETIME, date)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, date)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, info.twoGisLink)
        exif.setAttribute(
            ExifInterface.TAG_USER_COMMENT,
            "2GIS: ${info.twoGisLink} | GPS: %.6f,%.6f | weld markers: %d"
                .format(Locale.US, info.latitude, info.longitude, markerCount),
        )
        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "FenceReport")
        exif.saveAttributes()
    }

    private fun saveMediaStore(context: Context, src: File, name: String, info: PhotoInfo): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
            put(MediaStore.Images.Media.DATE_TAKEN, info.timeMillis)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Не удалось создать файл в галерее")
        try {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Не удалось открыть файл в галерее")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, src: File, name: String): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Нет доступа к папке ${dir.path}")
        val dest = File(dir, name)
        src.copyTo(dest, overwrite = true)
        // Ждём, пока галерея проиндексирует файл, чтобы получить content://-ссылку для «Поделиться».
        val latch = CountDownLatch(1)
        var scanned: Uri? = null
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("image/jpeg")) { _, uri ->
            scanned = uri
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return scanned ?: throw IllegalStateException("Галерея не увидела файл ${dest.path}")
    }
}

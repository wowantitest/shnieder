package kz.kokshetau.fencereport

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Экран разметки: ставим маркеры на места сварки и сохраняем в галерею. */
class EditorActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val STATE_MARKERS = "markers"
        private const val MAX_SIDE = 4096
    }

    private lateinit var imageView: MarkerImageView
    private lateinit var progress: ProgressBar
    private lateinit var saveButton: Button
    private lateinit var undoButton: Button
    private lateinit var retakeButton: Button

    private lateinit var photoFile: File
    private lateinit var info: PhotoInfo
    private var baseBitmap: Bitmap? = null
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        val parsed = PhotoInfo.from(intent)
        if (path == null || parsed == null || !File(path).exists()) {
            finish()
            return
        }
        photoFile = File(path)
        info = parsed

        setContentView(R.layout.activity_editor)
        imageView = findViewById(R.id.image)
        progress = findViewById(R.id.progress)
        saveButton = findViewById(R.id.save)
        undoButton = findViewById(R.id.undo)
        retakeButton = findViewById(R.id.retake)

        savedInstanceState?.getFloatArray(STATE_MARKERS)?.let { arr ->
            for (i in 0 until arr.size / 2) imageView.markers.add(PointF(arr[2 * i], arr[2 * i + 1]))
        }

        imageView.onMarkersChanged = { updateButtons() }
        undoButton.setOnClickListener { imageView.undo() }
        retakeButton.setOnClickListener { finish() }
        saveButton.setOnClickListener {
            if (imageView.markers.isEmpty()) {
                AlertDialog.Builder(this)
                    .setMessage("Маркер сварки не поставлен. Сохранить без маркера?")
                    .setPositiveButton("Сохранить") { _, _ -> save() }
                    .setNegativeButton("Отмена", null)
                    .show()
            } else {
                save()
            }
        }
        updateButtons()
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::imageView.isInitialized) {
            val arr = FloatArray(imageView.markers.size * 2)
            imageView.markers.forEachIndexed { i, p -> arr[2 * i] = p.x; arr[2 * i + 1] = p.y }
            outState.putFloatArray(STATE_MARKERS, arr)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && ::photoFile.isInitialized) photoFile.delete()
    }

    private fun updateButtons() {
        val ready = baseBitmap != null && !saving
        saveButton.isEnabled = ready
        retakeButton.isEnabled = !saving
        undoButton.isEnabled = ready && imageView.markers.isNotEmpty()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val src = decodeUpright(photoFile)
                    ReportRenderer.withInfoBar(src, info).also { src.recycle() }
                }
            }
            progress.visibility = View.GONE
            result.onSuccess {
                baseBitmap = it
                imageView.bitmap = it
                updateButtons()
            }.onFailure {
                Toast.makeText(this@EditorActivity, "Не удалось открыть фото: ${it.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    /** Загружает фото, уменьшает слишком большие и поворачивает по EXIF. */
    private fun decodeUpright(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("файл повреждён")

        val orientation = ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated != bmp) bmp.recycle()
        return rotated
    }

    private fun save() {
        val base = baseBitmap ?: return
        saving = true
        updateButtons()
        progress.visibility = View.VISIBLE
        val markers = imageView.markers.map { PointF(it.x, it.y) }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val finalBitmap = ReportRenderer.withMarkers(base, markers)
                    try {
                        ReportSaver.save(applicationContext, finalBitmap, info, markers.size)
                    } finally {
                        finalBitmap.recycle()
                    }
                }
            }
            progress.visibility = View.GONE
            saving = false
            updateButtons()
            result.onSuccess { uri -> showSaved(uri) }
                .onFailure {
                    Toast.makeText(this@EditorActivity, "Ошибка сохранения: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun showSaved(uri: android.net.Uri) {
        AlertDialog.Builder(this)
            .setTitle("Сохранено")
            .setMessage("Фото сохранено в галерею (Pictures/${ReportSaver.ALBUM}).\n\n${info.twoGisLink}")
            .setCancelable(false)
            .setPositiveButton("Новое фото") { _, _ -> finish() }
            .setNeutralButton("Поделиться") { _, _ ->
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, "${info.coordinatesText}\n${info.dateText}\n${info.twoGisLink}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "Отправить отчёт"))
                finish()
            }
            .show()
    }
}

package kz.kokshetau.fencereport

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File

/** Экран камеры: показывает текущие координаты и делает снимок. */
class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var captureButton: ImageButton
    private lateinit var grantButton: Button

    private var imageCapture: ImageCapture? = null
    private lateinit var fused: FusedLocationProviderClient
    private var lastLocation: Location? = null
    private var locationStarted = false
    private var capturing = false
    private var cameraStarted = false

    private val requiredPermissions: Array<String>
        get() {
            val list = mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                list += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
            return list.toTypedArray()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPermissions()) {
                grantButton.visibility = View.GONE
                startCamera()
                startLocation()
            } else {
                grantButton.visibility = View.VISIBLE
                statusText.text = "Нужны разрешения на камеру и геолокацию"
            }
        }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                lastLocation = it
                updateStatus()
            }
        }
    }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = rotation
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        statusText = findViewById(R.id.status)
        captureButton = findViewById(R.id.capture)
        grantButton = findViewById(R.id.grant)
        fused = LocationServices.getFusedLocationProviderClient(this)

        captureButton.setOnClickListener { takePhoto() }
        grantButton.setOnClickListener {
            if (requiredPermissions.any { !shouldShowRequestPermissionRationale(it) && !granted(it) }) {
                // Пользователь запретил навсегда — открываем настройки приложения.
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            } else {
                permissionLauncher.launch(requiredPermissions)
            }
        }

        if (hasPermissions()) startCamera() else permissionLauncher.launch(requiredPermissions)
        updateStatus()
    }

    override fun onStart() {
        super.onStart()
        orientationListener.enable()
        if (hasPermissions()) startLocation()
    }

    override fun onResume() {
        super.onResume()
        capturing = false
        if (hasPermissions()) {
            grantButton.visibility = View.GONE
            startCamera()
            startLocation()
        }
        updateStatus()
    }

    override fun onStop() {
        super.onStop()
        orientationListener.disable()
        fused.removeLocationUpdates(locationCallback)
        locationStarted = false
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasPermissions() = requiredPermissions.all { granted(it) }

    private fun startCamera() {
        if (cameraStarted) return
        cameraStarted = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                imageCapture = capture
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка камеры: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (locationStarted) return
        locationStarted = true
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        fused.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && lastLocation == null) {
                lastLocation = loc
                updateStatus()
            }
        }
    }

    private fun locationAgeSeconds(loc: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000_000L

    private fun updateStatus() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val loc = lastLocation
        statusText.text = when {
            !hasPermissions() -> "Нужны разрешения на камеру и геолокацию"
            !LocationManagerCompat.isLocationEnabled(lm) -> "Включите геолокацию (GPS) в настройках телефона"
            loc == null -> "Определяю координаты…"
            else -> {
                val info = loc.toPhotoInfo()
                val age = locationAgeSeconds(loc)
                val stale = if (age > 30) "\nВнимание: координаты устарели ($age с)" else ""
                info.coordinatesText + stale
            }
        }
        captureButton.isEnabled = loc != null && !capturing
    }

    private fun Location.toPhotoInfo() = PhotoInfo(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        altitude = if (hasAltitude()) altitude else null,
        timeMillis = System.currentTimeMillis(),
    )

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val loc = lastLocation
        if (loc == null) {
            Toast.makeText(this, "Нет координат — подождите GPS", Toast.LENGTH_SHORT).show()
            return
        }
        capturing = true
        updateStatus()

        val file = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val info = loc.toPhotoInfo()
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val intent = Intent(this@MainActivity, EditorActivity::class.java)
                    intent.putExtra(EditorActivity.EXTRA_PATH, file.absolutePath)
                    info.putInto(intent)
                    startActivity(intent)
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    updateStatus()
                    Toast.makeText(this@MainActivity, "Не удалось сделать фото: ${exception.message}",
                        Toast.LENGTH_LONG).show()
                }
            })
    }
}

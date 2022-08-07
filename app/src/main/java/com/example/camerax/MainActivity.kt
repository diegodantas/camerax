package com.example.camerax

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.core.net.toUri
import com.example.camerax.databinding.ActivityMainBinding
import com.squareup.picasso.Picasso
import java.io.File
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity() {

    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater)}
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var whichWay: Int = 0
    private lateinit var outputFile: File
    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var cameraControl: CameraControl
    private var torchIsEnable: Boolean = false

    private lateinit var directImages: File
    private lateinit var directVideos: File
    private lateinit var directVoices: File
    private lateinit var directDocuments: File

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        imageCapture = ImageCapture.Builder().build()

        if (allPermissionsGranted()) {
            startUiCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        binding.changeCamera.setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            startCamera()
        }

        binding.button.setOnClickListener {
            binding.changeCamera.visibility = View.GONE
            binding.flash.visibility = View.GONE
            binding.changeModeVideoButton.visibility = View.GONE
            binding.changeModePhotoButton.visibility = View.GONE
            when (whichWay) {
                PHOTO -> takePhoto()
                VIDEO -> captureVideo()
            }
        }

        binding.notChecked.setOnClickListener {
            outputFile.delete()
            binding.imgPhoto.visibility = View.GONE
            binding.checked.visibility = View.GONE
            binding.notChecked.visibility = View.GONE
            binding.videoView.visibility = View.GONE
            binding.viewFinder.visibility = View.VISIBLE
            binding.button.visibility = View.VISIBLE
            binding.changeCamera.visibility = View.VISIBLE
            binding.flash.visibility = View.VISIBLE
            binding.changeModeVideoButton.visibility = View.VISIBLE
            binding.changeModePhotoButton.visibility = View.VISIBLE
        }

        binding.changeModePhotoButton.setOnClickListener {
            startCamera()
            whichWay = PHOTO
            binding.changeModePhotoButton.setColorFilter(Color.RED)
            binding.changeModeVideoButton.setColorFilter(Color.WHITE)
            binding.button.setColorFilter(Color.WHITE)
        }

        binding.changeModeVideoButton.setOnClickListener {
            startCameraVideo()
            whichWay = VIDEO
            binding.changeModePhotoButton.setColorFilter(Color.WHITE)
            binding.changeModeVideoButton.setColorFilter(Color.RED)
            binding.button.setColorFilter(Color.RED)
        }

        binding.flash.setOnClickListener {
            if (!torchIsEnable) {
                torchIsEnable = true
                binding.flash.setImageResource(R.drawable.ic_flashlight)
                cameraControl.enableTorch(torchIsEnable) // enable torch
                Log.d(TAG, torchIsEnable.toString())
            } else {
                torchIsEnable = false
                binding.flash.setImageResource(R.drawable.ic_flashlight_off)
                cameraControl.enableTorch(torchIsEnable) // disbale torch
                Log.d(TAG, torchIsEnable.toString())
            }
        }

        binding.viewFinder.setOnTouchListener { _ , motionEvent ->
            when(motionEvent.action) {
                MotionEvent.ACTION_DOWN -> return@setOnTouchListener true
                MotionEvent.ACTION_UP -> {
                    Log.d("default", "MotionEvent.ACTION_UP")
                    // Get the MeteringPointFactory from PreviewView
                    val factory = binding.viewFinder.meteringPointFactory

                    // Create a MeteringPoint from the tap coordinates
                    val point = factory.createPoint(motionEvent.x, motionEvent.y)

                    // Create a MeteringAction from the MeteringPoint, you can configure it to specify the metering mode
                    val action = FocusMeteringAction.Builder(point).build()

                    // Trigger the focus and metering. The method returns a ListenableFuture since the operation
                    // is asynchronous. You can use it get notified when the focus is successful or if it fails.
                    cameraControl.startFocusAndMetering(action)

                    return@setOnTouchListener true
                }
                else -> {return@setOnTouchListener false}
            }
        }
    }

    private fun startUiCamera() {
        checkDirectories()
        startCamera()
        binding.changeModePhotoButton.setColorFilter(Color.RED)
        whichWay = PHOTO
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCameraVideo() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider
                    .bindToLifecycle(this, cameraSelector, preview, videoCapture)
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            cameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle( this, cameraSelector, preview, imageCapture)

                // Get a camera instance
                val camera = cameraProvider.bindToLifecycle(this, cameraSelector)

                // Get a cameraControl instance
                cameraControl = camera.cameraControl

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        binding.button.isEnabled = false

        // Create name of file
        val uuid: UUID = UUID.randomUUID()

        // Initialize a new file instance to save bitmap object
        val file = File(directImages,"${uuid}.jpg")

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(file)
            .build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults){
                    outputFile = file

                    Picasso.get().load(output.savedUri).resize(600, 600).centerInside().into(binding.imgPhoto)
                    binding.imgPhoto.visibility = View.VISIBLE
                    binding.checked.visibility = View.VISIBLE
                    binding.notChecked.visibility = View.VISIBLE
                    binding.viewFinder.visibility = View.GONE
                    binding.button.visibility = View.GONE
                    binding.changeCamera.visibility = View.GONE
                    binding.flash.visibility = View.GONE

                    val msg = "Photo capture succeeded: ${output.savedUri?.encodedPath}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )
    }

    // Implements VideoCapture use case, including start and stop capturing.
    @SuppressLint("RestrictedApi")
    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return

        binding.button.isEnabled = false

        val curRecording = recording
        if (curRecording != null) {
            // Stop the current recording session.
            curRecording.stop()
            recording = null
            return
        }

        // Create name of file
        val uuid: UUID = UUID.randomUUID()

        // Initialize a new file instance to save bitmap object
        val file = File(directVideos,"${uuid}.mp4")

        val mediaStoreOutputOptions =  FileOutputOptions
            .Builder(file)
            .build()

        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (PermissionChecker.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                    PermissionChecker.PERMISSION_GRANTED)
                {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when(recordEvent) {
                    is VideoRecordEvent.Start -> {
                        binding.button.apply {
                            setColorFilter(Color.RED)
                            binding.chronometer.visibility = View.VISIBLE
                            binding.chronometer.base = SystemClock.elapsedRealtime()
                            binding.chronometer.start()
                            isEnabled = true
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "Video capture succeeded: " +
                                    "${recordEvent.outputResults.outputUri}"
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT)
                                .show()
                            Log.d(TAG, msg)
                            binding.imgPhoto.visibility = View.VISIBLE
                            binding.checked.visibility = View.VISIBLE
                            binding.notChecked.visibility = View.VISIBLE
                            binding.viewFinder.visibility = View.GONE
                            binding.button.visibility = View.GONE
                            binding.changeCamera.visibility = View.GONE
                            binding.flash.visibility = View.GONE
                            outputFile = file
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: " +
                                    "${recordEvent.error}")
                        }
                        binding.button.apply {
                            setColorFilter(Color.WHITE)
                            binding.chronometer.base = SystemClock.elapsedRealtime()
                            binding.chronometer.stop()
                            binding.chronometer.visibility = View.GONE
                            videoViewController()
                            isEnabled = true
                        }
                    }
                }
            }
    }

    private fun videoViewController() {
        val uri = outputFile.toUri()

        val mediaController = object : MediaController(this) {
            override fun show(timeout: Int) {
                super.show(0)
            }
        }
        binding.videoView.setMediaController(mediaController)
        binding.videoView.setVideoURI(uri)
        binding.videoView.isFocusable = true
        binding.videoView.visibility = View.VISIBLE
    }

    private fun checkDirectories() {

        directImages = File(externalCacheDir, "Imagens/")
        directVideos = File(externalCacheDir, "Videos/")
        directVoices = File(externalCacheDir, "Audios/")
        directDocuments = File(externalCacheDir, "Documents/")

        if (directImages.exists() && directDocuments.exists() && directVideos.exists() && directVoices.exists()) {
            Log.d("Faztuudu", "Diretorios existentes")
        } else {
            Log.d("Faztuudu", "Criando diretorios")
            directImages.mkdirs()
            directVideos.mkdirs()
            directVoices.mkdirs()
            directDocuments.mkdirs()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
//                binding.changeModePhotoButton.setColorFilter(Color.RED)
                startUiCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val PHOTO = 1
        private const val VIDEO = 0
        private const val TAG = "default"
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
package com.example.consciouscart

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var resultTextView: TextView
    private lateinit var cameraExecutor: ExecutorService
    private var isProcessingBarcode = false

    private var cameraControl: CameraControl? = null
    private var isTorchOn = false

    private val uniqueIds = HashSet<String>() // Use a HashSet to store unique IDs

    @SuppressLint("SetTextI18n", "MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        resultTextView = findViewById(R.id.resultTextView)
        cameraExecutor = Executors.newSingleThreadExecutor()

        val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
                if (isGranted) {
                    startCamera()
                } else {
                    resultTextView.text = "Camera permission is required"
                }
            }

        requestPermissionLauncher.launch(Manifest.permission.CAMERA)

        // Make resultTextView selectable
        resultTextView.setTextIsSelectable(true)
        // Set long click listener
        resultTextView.setOnLongClickListener {
            copyTextToClipboard()
            true
        }
    }

    private fun copyTextToClipboard() {
        // Create a copy of uniqueIds to avoid concurrent modification issues
        val idsToCopy = HashSet(uniqueIds)
        if (idsToCopy.isNotEmpty()) {
            val textToCopy = idsToCopy.joinToString("\n")
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newPlainText("Unique IDs", textToCopy)
            clipboardManager.setPrimaryClip(clipData)
            Toast.makeText(this, "Unique IDs copied to clipboard", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No Unique IDs to copy", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, this::analyzeImage)
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)

            cameraControl = camera.cameraControl // flashlight control

            findViewById<TextView>(R.id.btnFlashlight).setOnClickListener {
                isTorchOn = !isTorchOn
                cameraControl?.enableTorch(isTorchOn)
            }

        }, ContextCompat.getMainExecutor(this))
    }


    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    @SuppressLint("SetTextI18n")
    private fun analyzeImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && !isProcessingBarcode) {
            isProcessingBarcode = true
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build()

            val scanner = BarcodeScanning.getClient(options)

            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    Log.d("MainActivity", "Barcodes found: ${barcodes.size}")
                    if (barcodes.isNotEmpty()) {
                        for (barcode in barcodes) {
                            handleBarcode(barcode)
                        }
                    }
                    imageProxy.close()
                    isProcessingBarcode = false
                }
                .addOnFailureListener {
                    Log.e("MainActivity", "Barcode scan failed", it)
                    runOnUiThread {
                        resultTextView.text = "Scan failed"
                    }
                    imageProxy.close()
                    isProcessingBarcode = false
                }
        } else {
            imageProxy.close()
        }
    }
    @SuppressLint("SetTextI18n")
    private fun handleBarcode(barcode: Barcode) {
        val value = barcode.rawValue ?: barcode.displayValue

        if (value != null) {
            runOnUiThread {
                if (value.startsWith("http")) {
                    openUrlInBrowser(value)
                } else {
                    updateDisplayedId(value)  // show only the latest scanned value
                }
            }
        } else {
            Log.e("MainActivity", "Barcode value is null")
            runOnUiThread {
                resultTextView.text = "No barcode value"
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateDisplayedId(latestId: String) {
        resultTextView.text = latestId
    }

    private fun openUrlInBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid URL", Toast.LENGTH_SHORT).show()
            Log.e("MainActivity", "Failed to open URL", e)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateDisplayedIds() {
        // Create a copy of uniqueIds to avoid concurrent modification issues
        val idsToDisplay = HashSet(uniqueIds)
        val idsText = idsToDisplay.joinToString("\n")
        resultTextView.text = idsText
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
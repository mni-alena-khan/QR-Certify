package com.example.qr_certify

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.qr_certify.databinding.ActivityMainBinding
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedImageUri: Uri? = null
    private val db = FirebaseFirestore.getInstance()

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            binding.ivCertificatePreview.setImageURI(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Verifier Side: Start Scanner
        binding.btnScan.setOnClickListener {
            startActivity(Intent(this, ScannerActivity::class.java))
        }

        // Admin Side: Select Certificate Image
        binding.btnSelectImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        // Admin Side: Upload and Generate
        binding.btnGenerate.setOnClickListener {
            val name = binding.etRecipientName.text.toString().trim()
            val course = binding.etCourseName.text.toString().trim()

            if (name.isEmpty() || course.isEmpty() || selectedImageUri == null) {
                Toast.makeText(this, "Please fill all fields and select an image", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            uploadToCloudinary(name, course)
        }

        // Professional QR Save Handler
        binding.btnSaveQr.setOnClickListener {
            saveQrToGallery()
        }
    }

    private fun saveQrToGallery() {
        val drawable = binding.ivQrCode.drawable
        if (drawable == null || drawable !is BitmapDrawable) {
            Toast.makeText(this, "No QR to save yet", Toast.LENGTH_SHORT).show()
            return
        }

        val bitmap = drawable.bitmap
        val filename = "QR_${System.currentTimeMillis()}.png"
        var outputStream: OutputStream? = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/QR_Certify")
                }
                val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (imageUri != null) {
                    outputStream = resolver.openOutputStream(imageUri)
                }
            } else {
                val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString()
                val dir = File(imagesDir, "QR_Certify")
                if (!dir.exists()) dir.mkdirs()
                val image = File(dir, filename)
                outputStream = FileOutputStream(image)

                @Suppress("DEPRECATION")
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(image)))
            }

            outputStream?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                Toast.makeText(this, "QR Code saved to Gallery!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to save QR Code: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun uploadToCloudinary(name: String, course: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnGenerate.isEnabled = false

        selectedImageUri?.let { uri ->
            Thread {
                try {
                    val cloudName = "dt4unxor3"
                    val preset = "qr_certify_upload"

                    val inputStream = contentResolver.openInputStream(uri)
                    val bytes = inputStream?.readBytes()
                    inputStream?.close()

                    if (bytes != null) {
                        val boundary = "Boundary-" + System.currentTimeMillis()
                        val url = URL("https://api.cloudinary.com/v1_1/$cloudName/image/upload")
                        val connection = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            doOutput = true
                            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                        }

                        connection.outputStream.use { os ->
                            os.write("--$boundary\r\n".toByteArray())
                            os.write("Content-Disposition: form-data; name=\"upload_preset\"\r\n\r\n".toByteArray())
                            os.write("$preset\r\n".toByteArray())

                            os.write("--$boundary\r\n".toByteArray())
                            os.write("Content-Disposition: form-data; name=\"file\"; filename=\"cert.jpg\"\r\n".toByteArray())
                            os.write("Content-Type: image/jpeg\r\n\r\n".toByteArray())
                            os.write(bytes)
                            os.write("\r\n--$boundary--\r\n".toByteArray())
                        }

                        val responseCode = connection.responseCode
                        val responseString = if (responseCode == 200) {
                            connection.inputStream.bufferedReader().use { it.readText() }
                        } else {
                            connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                        }

                        val urlMatch = Regex(""""secure_url"\s*:\s*"([^"]+)"""").find(responseString)
                        val imageUrl = urlMatch?.groupValues?.get(1)

                        runOnUiThread {
                            if (imageUrl != null) {
                                saveToFirestore(name, course, imageUrl)
                            } else {
                                binding.progressBar.visibility = View.GONE
                                binding.btnGenerate.isEnabled = true
                                Toast.makeText(this@MainActivity, "Upload failed: bad response", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        runOnUiThread {
                            binding.progressBar.visibility = View.GONE
                            binding.btnGenerate.isEnabled = true
                            Toast.makeText(this@MainActivity, "Could not read image file", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        binding.btnGenerate.isEnabled = true
                        Toast.makeText(this@MainActivity, "Upload error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        } ?: run {
            binding.progressBar.visibility = View.GONE
            binding.btnGenerate.isEnabled = true
        }
    }

    private fun saveToFirestore(name: String, course: String, imageUrl: String) {
        val certificateId = "CERT-" + UUID.randomUUID().toString().substring(0, 8).uppercase()
        val certificate = Certificate(
            certificateId = certificateId,
            recipientName = name,
            courseName = course,
            issueDate = Timestamp.now(),
            imageUrl = imageUrl,
            isVerified = true
        )

        db.collection("certificates")
            .document(certificateId)
            .set(certificate)
            .addOnSuccessListener {
                generateQrCode(certificateId)
                binding.progressBar.visibility = View.GONE
                binding.btnGenerate.isEnabled = true
                Toast.makeText(this, "Certificate saved and QR generated!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                binding.btnGenerate.isEnabled = true
                Toast.makeText(this, "Firestore error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun generateQrCode(certificateId: String) {
        try {
            val barcodeEncoder = BarcodeEncoder()
            val bitmap: Bitmap = barcodeEncoder.encodeBitmap(certificateId, BarcodeFormat.QR_CODE, 400, 400)
            binding.ivQrCode.setImageBitmap(bitmap)
            binding.ivQrCode.visibility = View.VISIBLE
            binding.tvQrInstruction.visibility = View.VISIBLE
            binding.tvQrInstruction.text = "Certificate ID: $certificateId"

            // Show the save button
            binding.btnSaveQr.visibility = View.VISIBLE
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
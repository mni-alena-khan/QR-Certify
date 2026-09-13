package com.example.qr_certify

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import coil.load
import com.example.qr_certify.databinding.ActivityValidationResultBinding
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

class ValidationResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityValidationResultBinding
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityValidationResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val certificateId = intent.getStringExtra("CERTIFICATE_ID")?.trim()
        if (!certificateId.isNullOrEmpty()) {
            verifyCertificateDirectly(certificateId)
        } else {
            showError("Invalid QR Code ID")
        }

        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun verifyCertificateDirectly(id: String) {
        binding.tvStatus.text = "Verifying ID: $id"
        binding.progressBar.visibility = View.VISIBLE

        db.collection("certificates").document(id)
            .get()
            .addOnSuccessListener { document ->
                binding.progressBar.visibility = View.GONE
                if (document != null && document.exists()) {
                    val data = document.data
                    if (data != null) {
                        displayCertificate(data)
                    } else {
                        showError("Data format error")
                    }
                } else {
                    showError("Certificate not found")
                }
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                showError("Verification failed: ${e.message}")
            }
    }

    private fun displayCertificate(data: Map<String, Any>) {
        binding.tvStatus.text = "VERIFIED CERTIFICATE"
        binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        binding.cvDetails.visibility = View.VISIBLE

        binding.tvRecipient.text = data["recipientName"] as? String ?: "Unknown"
        binding.tvCourse.text = data["courseName"] as? String ?: "Unknown"
        binding.tvCertId.text = "ID: ${data["certificateId"] as? String ?: ""}"

        // Handle Firestore Timestamp or map fallback
        val issueDateObj = data["issueDate"]
        val date: Date = when (issueDateObj) {
            is Timestamp -> issueDateObj.toDate()
            is Map<*, *> -> {
                val seconds = (issueDateObj["_seconds"] as? Number)?.toLong() ?: 0
                Date(seconds * 1000)
            }
            else -> Date()
        }
        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        binding.tvDate.text = "Issued on: ${sdf.format(date)}"

        val imageUrl = data["imageUrl"] as? String
        if (!imageUrl.isNullOrEmpty()) {
            binding.ivCertificateImage.load(imageUrl)
        }
    }

    private fun showError(message: String) {
        binding.tvStatus.text = "INVALID CERTIFICATE"
        binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
        binding.cvDetails.visibility = View.GONE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
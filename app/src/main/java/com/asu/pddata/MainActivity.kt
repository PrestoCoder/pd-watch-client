package com.asu.pddata

import ValidationResponse
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.asu.pddata.constants.Constants
import com.asu.pddata.databinding.ActivityMainBinding
import com.asu.pddata.service.ForegroundService
import kotlinx.coroutines.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MainActivity : Activity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(Dispatchers.Main)

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        createNotificationChannel()

        val sharedPreferences = getSharedPreferences("appPreferences", Context.MODE_PRIVATE)

        // Check if "patientID" exists, if not set to "none"
        if (!sharedPreferences.contains("patientID")) {
            sharedPreferences.edit().putString("patientID", "none").apply()
        }


        val patientId = sharedPreferences.getString("patientID", "none");
        binding.statusTextView.text = "Patient ID: $patientId"

        // Start the ForegroundService with patient ID
        // We will try not to stop foreground service even with no patient, for now.
        // Just to be extra sure that its always running.
        // We simply will block the service from saving/sending any data if patientID is none.
        val serviceIntent = Intent(this, ForegroundService::class.java).apply {
            putExtra("patientID", patientId)
        }
        startService(serviceIntent)

        // Even Listener for taking medication
        binding.button.setOnClickListener {
            val flagIntent = Intent(this, ForegroundService::class.java).apply {
                action = ForegroundService.ACTION_SET_FLAG
                putExtra("patientID", patientId)
            }
            startService(flagIntent)
        }

        // Event Listener for validate patient submit button
        binding.submitButton.setOnClickListener {
            val newPatientID = binding.editText.text.toString()
            val previousPatientID = sharedPreferences.getString("patientID", "none") ?: "none"

            validatePatientID(newPatientID, previousPatientID) { isValid, message ->
                if (isValid) {
                    with(sharedPreferences.edit()) {
                        putString("patientID", newPatientID)
                        apply() // using apply() for asynchronous commit
                    }
                    binding.statusTextView.text = "Patient ID: $newPatientID"

                    // Broadcast the new patient ID to the service
                    Intent(this, ForegroundService::class.java).also { intent ->
                        intent.action = ForegroundService.ACTION_UPDATE_PATIENT_ID
                        intent.putExtra(ForegroundService.EXTRA_PATIENT_ID, newPatientID)
                        startService(intent) // This doesn't restart the service but delivers the intent
                    }

                    Toast.makeText(this, "Patient ID updated successfully. $message", Toast.LENGTH_SHORT).show()
                } else {
                    // Show error message to the user
                    Toast.makeText(this, "Validation failed. $message Please try again!", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Calls server to check if patient ID sent is valid, and not already has watch mapped to it.
    private fun validatePatientID(currentPatientID: String, previousPatientID: String, callback: (Boolean, Any?) -> Unit) {
        RetrofitClient.instance.validateUser(currentPatientID, previousPatientID).enqueue(object :
            Callback<ValidationResponse> {
            override fun onResponse(call: Call<ValidationResponse>, response: Response<ValidationResponse>) {
                if (response.isSuccessful) {
                    // Check if the message indicates the user can be connected
                    val result = response.body()?.message.orEmpty()
                    callback(true, result)
                } else {
                    // Log or handle the specific error case here
                    val message = response.errorBody()?.string()
                    Log.e("API Error", "Error validating user: ${message}")
                    callback(false, message)
                }
            }

            override fun onFailure(call: Call<ValidationResponse>, t: Throwable) {
                // Log the error message to the console
                Log.e("API Error", "Failed to reach the server", t)
                callback(false, "Failed to reach the server")
            }
        })
    }


    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_ID,
            Constants.NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = Constants.NOTIFICATION_CHANNEL_DESCRIPTION
        }
        val notificationManager: NotificationManager =
            getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}
package com.asu.pddata.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.util.Log
import com.asu.pddata.constants.Constants
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import okhttp3.MultipartBody
import okhttp3.RequestBody

import ApiService
import RetrofitClient
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Looper
import androidx.core.content.ContextCompat



class ForegroundService : Service(), SensorEventListener {

    companion object {
        const val ACTION_SET_FLAG = "com.asu.pddata.action.SET_FLAG"
        const val ACTION_UPDATE_PATIENT_ID = "com.asu.pddata.action.UPDATE_PATIENT_ID"
        const val EXTRA_PATIENT_ID = "extra_patient_id"

    }
    private var patientId: String? = null
    private lateinit var patientIdReceiver: BroadcastReceiver

    private val networkChangeReceiver = NetworkChangeReceiver()
    private var isServiceRunning = false
    private var mSensorManager: SensorManager? = null
    private var mAccSensor: Sensor? = null
    private var mGyroSensor: Sensor? = null
    private var mHeartRateSensor: Sensor? = null

    private var accXValue: Float = 0F
    private var accYValue: Float = 0F
    private var accZValue: Float = 0F
    private var angularSpeedX: Float = 0F
    private var angularSpeedY: Float = 0F
    private var angularSpeedZ: Float = 0F
    private var heartRate: Float = 0F

    private var accXValues: MutableList<Float> = arrayListOf()
    private var accYValues: MutableList<Float> = arrayListOf()
    private var accZValues: MutableList<Float> = arrayListOf()
    private var angularSpeedXValues: MutableList<Float> = arrayListOf()
    private var angularSpeedYValues: MutableList<Float> = arrayListOf()
    private var angularSpeedZValues: MutableList<Float> = arrayListOf()
    private var heartRateValues: MutableList<Float> = arrayListOf()
    // Keeping it float, to avoid array type issues for now.
    private var medicationTakenValues: MutableList<Float> = arrayListOf()

    private var medicationTaken: Float = 0F  // Initially, medication is not taken.

    private val DATA_COLLECTION_INTERVAL = 1000 // 1 second
    private val ClOUD_SYNC_INTERVAL = 10000 // 10 second,
    private val headers: List<String> = listOf("Acc X", "Acc Y", "Acc Z", "Angular X",
        "Angular Y", "Angular Z", "Heart Rate", "Medication Taken")

    private val dataCollectionHandler = Handler()
    private val cloudSyncHandler = Handler()
    // This is time when csv is saved, not when its sent to server
    private var lastSynced = System.currentTimeMillis()

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    @SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        patientId = intent?.getStringExtra("patientID") ?: "none"

        if (intent?.action == ACTION_SET_FLAG) {
            setMedicationTakenFlag(1F)
        }

        if (intent?.action == ACTION_UPDATE_PATIENT_ID) {
            updatePatientIdFromIntent(intent)
        }

        if (!isServiceRunning) {
            isServiceRunning = true
            startForeground()
        }
        // Service will be restarted if killed by the system
        return START_STICKY
    }

    private fun registerPatientIdReceiver() {
        patientIdReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val newPatientId = intent?.getStringExtra(EXTRA_PATIENT_ID)
                if (newPatientId != null) {
                    updatePatientId(newPatientId)
                }
            }
        }
        val filter = IntentFilter(ACTION_UPDATE_PATIENT_ID)
        // Use ContextCompat for better handling across different API levels
        val context = ContextCompat.createDeviceProtectedStorageContext(this) ?: this
        // Specify RECEIVER_NOT_EXPORTED and use updated Handler creation
        context.registerReceiver(patientIdReceiver, filter, null, Handler(), Context.RECEIVER_NOT_EXPORTED)
    }

    private fun updatePatientId(newPatientId: String) {
        // Logic to update patient ID in the service
        this.patientId = newPatientId
        Log.i("ForegroundService", "Patient ID updated to: $newPatientId")
    }

    fun sendUnsentFiles() {
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val files = directory.listFiles { _, name -> name.endsWith(".csv") }
        files?.forEach { file ->
            sendDataToServer(
                csvFile = file,
                userName = "unknown",  // These values might need to be dynamically determined based on your app's logic
                studyName = "test1",
                applicationType = "android",
                numRows = "Unknown",  // You might want to extract this information from the file itself or maintain metadata elsewhere
                startTimestamp = "Unknown",  // Same comment as above
                endTimestamp = "Unknown",  // Same comment as above
                onSuccess = {
                    if (file.delete()) {
                        Log.v("Cloud", "File deleted after successful upload: ${file.name}")
                    } else {
                        Log.e("Cloud", "Failed to delete file after upload: ${file.name}")
                    }
                },
                onFailure = { exception ->
                    Log.e("Cloud", "Failed to send file to server: ${file.name}, ${exception?.message}")
                }
            )
        }
    }

    private fun updatePatientIdFromIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_PATIENT_ID)?.let { newPatientId ->
            updatePatientId(newPatientId)
            // For now, if patientID changes midway, discard incomplete data( of < 10s) for the previous patient.
            if(newPatientId != "none") {
                clearDataAndResetTimestamp();
            }
        }
    }

    private fun setMedicationTakenFlag(value: Float) {
        medicationTaken = value
        // Optionally log or handle the flag change
        Log.i("ForegroundService", "Medication taken flag set to: $medicationTaken")
    }

    fun resetMedicationFlag() {
        medicationTaken = 0F
        Log.i("ForegroundService", "Medication taken flag reset.")
    }

    @SuppressLint("ForegroundServiceType")
    private fun startForeground() {
        val notification = Notification.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Service")
            .setContentText("Collecting data")
            .build()

        startForeground(1, notification)
    }

    override fun onCreate() {
        super.onCreate()

        sendUnsentFiles()  // Check and send any unsent files
        registerNetworkChangeReceiver()
        registerPatientIdReceiver()

        mSensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        mAccSensor = mSensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        mGyroSensor = mSensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        mHeartRateSensor = mSensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)

        mSensorManager?.registerListener(this, mAccSensor, SensorManager.SENSOR_DELAY_NORMAL)
        mSensorManager?.registerListener(this, mGyroSensor, SensorManager.SENSOR_DELAY_NORMAL)
        mSensorManager?.registerListener(this, mHeartRateSensor, SensorManager.SENSOR_DELAY_NORMAL)

        startDataCollection()
    }

    private fun registerNetworkChangeReceiver() {
        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        val context = ContextCompat.createDeviceProtectedStorageContext(this) ?: this
        // Specify RECEIVER_NOT_EXPORTED and use updated Handler creation
        context.registerReceiver(networkChangeReceiver, filter, null, Handler(), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        stopDataCollection()
        mSensorManager?.unregisterListener(this)
        unregisterReceiver(networkChangeReceiver)
        unregisterReceiver(patientIdReceiver)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // If sensor is unreliable, then just return
        if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            return
        }
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            accXValue = event.values[0]
            accYValue = event.values[1]
            accZValue = event.values[2]
        } else if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            angularSpeedX = event.values[0]
            angularSpeedY = event.values[1]
            angularSpeedZ = event.values[2]
        } else if (event.sensor.type == Sensor.TYPE_HEART_RATE) {
            heartRate = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        //do something
    }

    // Saving to csv only if patientID is not "none"
    // And also if there is space to write on external storage, although this should be mostly possible.
    private fun saveDataToCSV(headers: List<String>, data: List<List<Float>>, fileName: String): File? {
        if (isExternalStorageWritable() && patientId != "none") {
            val csvFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), fileName)

            try {
                Log.v("Cloud", "Saving file to $fileName")
                val fileWriter = FileWriter(csvFile)

                fileWriter.append(headers.joinToString(","))
                fileWriter.append("\n")

                if (data.isNotEmpty()) {
                    for (i in 0 until data[0].size) {
                        val row: MutableList<String> = mutableListOf()
                        for (sensor in data) {
                            row.add(String.format(Locale.US, "%.2f", sensor[i]))
                        }
                        fileWriter.append(row.joinToString(","))
                        fileWriter.append("\n")
                    }
                } else {
                    Log.i("data", "List is empty")
                }

                fileWriter.close()
                clearDataAndResetTimestamp();
                return csvFile // Return the File object on success
            } catch (e: IOException) {
                e.printStackTrace()
                return null // Return null if saving fails
            }
        }
        return null // Return null if external storage is not writable
    }

    private fun clearDataAndResetTimestamp() {
        accXValues.clear()
        accYValues.clear()
        accZValues.clear()
        angularSpeedXValues.clear()
        angularSpeedYValues.clear()
        angularSpeedZValues.clear()
        heartRateValues.clear()
        medicationTakenValues.clear()
        // When file saved, lastSynced should be changed.
        // Can't rely on cloud sync for this, as that might not happen.
        // And multiple files might get same last sync time.
        lastSynced = System.currentTimeMillis()
    }

    private fun sendDataToServer(
        csvFile: File,
        userName: String,
        studyName: String,
        applicationType: String,
        numRows: String,
        startTimestamp: String,
        endTimestamp: String,
        onSuccess: () -> Unit,
        onFailure: (Exception?) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val apiService = RetrofitClient.instance

                val userNameBody = RequestBody.create(MultipartBody.FORM, userName)
                val studyNameBody = RequestBody.create(MultipartBody.FORM, studyName)
                val applicationTypeBody = RequestBody.create(MultipartBody.FORM, applicationType)
                val numRowsBody = RequestBody.create(MultipartBody.FORM, numRows)
                val startTimestampBody = RequestBody.create(MultipartBody.FORM, startTimestamp)
                val endTimestampBody = RequestBody.create(MultipartBody.FORM, endTimestamp)
                val requestFile = RequestBody.create(MultipartBody.FORM, csvFile)
                val filePart = MultipartBody.Part.createFormData("file", csvFile.name, requestFile)

                val response = apiService.uploadSensorData(
                    userNameBody,
                    studyNameBody,
                    applicationTypeBody,
                    numRowsBody,
                    startTimestampBody,
                    endTimestampBody,
                    filePart
                )

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        val responseBody = response.body()
                        val rawResponse = responseBody?.string() ?: "No response body"
                        Log.v("Cloud", "Raw Response: $rawResponse")
                        onSuccess()
                    } else {
                        Log.e("Cloud", "Failed to send data to server: ${response.message()}")
                        onFailure(null)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Log.e("Cloud", "Network request failed", e)
                    onFailure(e)
                }
            }
        }
    }

    private fun isExternalStorageWritable(): Boolean {
        val state = Environment.getExternalStorageState()
        return Environment.MEDIA_MOUNTED == state
    }

    private val dataCollectionRunnable = object : Runnable {
        override fun run() {
            collectData()

            dataCollectionHandler.postDelayed(this, DATA_COLLECTION_INTERVAL.toLong())
        }
    }

    private val cloudSyncRunnable = object : Runnable {
        override fun run() {
            val data: List<List<Float>> = listOf(accXValues, accYValues, accZValues,
                angularSpeedXValues, angularSpeedYValues, angularSpeedZValues, heartRateValues, medicationTakenValues)
            val currentSync = System.currentTimeMillis()
            val lastSyncedTemp = lastSynced;
            val csvFile = saveDataToCSV(headers, data, "$patientId-data-$lastSynced-$currentSync.csv")

            sendUnsentFiles()  // Check and send any unsent files

            if (csvFile != null && patientId != "none") {
                sendDataToServer(
                    csvFile,
                    userName = patientId!!,
                    studyName = "test1",
                    applicationType = "android",
                    numRows = data[0].size.toString(),
                    startTimestamp = lastSyncedTemp.toString(),
                    endTimestamp = currentSync.toString(),
                    onSuccess = {
                        // Data was successfully sent to the server, delete the file
                        if (csvFile.delete()) {
                            Log.v("Cloud", "File deleted: ${csvFile.name}")
                        } else {
                            Log.e("Cloud", "Failed to delete file: ${csvFile.name}")
                        }
                    },
                    onFailure = { exception ->
                        Log.e("Cloud", "Failed to send data to server: ${exception?.message}")
                    }
                )
            }
            cloudSyncHandler.postDelayed(this, ClOUD_SYNC_INTERVAL.toLong())
        }
    }

    private fun startDataCollection() {
        dataCollectionHandler.post(dataCollectionRunnable)
        cloudSyncHandler.post(cloudSyncRunnable)
    }

    private fun stopDataCollection() {
        dataCollectionHandler.removeCallbacks(dataCollectionRunnable)
        cloudSyncHandler.removeCallbacks(cloudSyncRunnable)
    }

    fun collectData() {
        if(patientId != "none") {
            Log.v("Collect", "Collecting data")
            accXValues.add(accXValue)
            accYValues.add(accYValue)
            accZValues.add(accZValue)
            angularSpeedXValues.add(angularSpeedX)
            angularSpeedYValues.add(angularSpeedY)
            angularSpeedZValues.add(angularSpeedZ)
            heartRateValues.add(heartRate)
            medicationTakenValues.add(medicationTaken)
            // We reset it here only, so that multiple timestamps don't show medication was taken
            if(medicationTaken == 1F) {
                resetMedicationFlag()
            }
        }

    }

}
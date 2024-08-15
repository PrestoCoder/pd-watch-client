package com.asu.pddata.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log

// This is to check network changes
// This calls to send unsent files once network is back.
// Needs to be handled separately, as service is not interrupted when internet goes down.
// Handling of unsent files on restart of ForeGround service is handled there itself.
class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (ConnectivityManager.CONNECTIVITY_ACTION == intent.action) {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkInfo = connectivityManager.activeNetworkInfo
            // Check if the network is connected or not
            if (networkInfo != null && networkInfo.isConnected) {
                // Network is available
                Log.i("NetworkChangeReceiver", "Network available")
                if (context is ForegroundService) {
                    context.sendUnsentFiles()
                }
            }
        }
    }
}

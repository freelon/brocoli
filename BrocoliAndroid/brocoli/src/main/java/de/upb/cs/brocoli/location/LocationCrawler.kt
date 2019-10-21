package de.upb.cs.brocoli.location

import android.content.Context
import android.util.Log
import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.instance
import com.google.android.gms.location.*
import de.upb.cs.brocoli.database.LocationUpdate
import de.upb.cs.brocoli.database.LocationUpdateDao

class LocationCrawler(private val kodein: Kodein) {
    companion object {
        private val TAG = LocationCrawler::class.java.simpleName
        private const val INTERVAL = 60_000L
    }

    private val context: Context = kodein.instance()
    private val locationDao: LocationUpdateDao = kodein.instance()
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val locationRequest = LocationRequest().apply {
        interval = INTERVAL
        fastestInterval = INTERVAL / 2
        priority = LocationRequest.PRIORITY_HIGH_ACCURACY
    }
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(p0: LocationResult?) {
            if (p0 != null) {
                val location = p0.lastLocation ?: return
                val update = LocationUpdate(location.time, location.accuracy, location.latitude, location.longitude)
                locationDao.addLocationUpdate(update)
                Log.d(TAG, "Received update: $update")
            }
        }
    }

    fun start() {
        Log.d(TAG, "Starting..")
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        LocationServices.getSettingsClient(context).checkLocationSettings(builder.build()).addOnSuccessListener {
            Log.d(TAG, "Received: $it")
            try {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
            } catch (e: SecurityException) {
                Log.e(TAG, "Couldn't register for updates", e)
            }
        }.addOnFailureListener { exception ->
            Log.e(TAG, "couldn't start", exception)
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping..")
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}

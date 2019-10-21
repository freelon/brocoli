package de.upb.cs.brocoli.library

import android.app.job.JobParameters
import android.app.job.JobService
import android.arch.persistence.room.Room
import android.content.Context
import android.os.AsyncTask
import android.os.Build
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationManagerCompat
import android.util.Log
import android.widget.Toast
import de.upb.cs.brocoli.R
import de.upb.cs.brocoli.connectivity.NearbyConnectivity
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.*
import android.app.NotificationManager
import android.app.NotificationChannel
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import de.upb.cs.brocoli.database.*
import retrofit2.http.Path


const val UPLOAD_URL = "http://spielwiese.cs.upb.de:8080/"

class LogContents(val deviceId: String,
                  val timestamp: Long,
                  val content: List<DbLogEvent>,
                  val bytesSent: Int,
                  val bytesReceived: Int,
                  val deviceModel: String,
                  val deviceOsVersion: String,
                  val bluetoothLeCapable: Boolean,
                  val bluetoothLeBeaconCapable: Boolean,
                  val gpsLogs: List<LocationUpdate>)

internal const val LOG = "LogUpload"

fun uploadLog(context: Context, logContents: LogContents): Boolean {
    val (messageToUser, success) = try {
        val uploadService = Retrofit.Builder()
                .addConverterFactory(GsonConverterFactory.create())
                .baseUrl(UPLOAD_URL)
                .client(OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            // Log.i(TAG, "GzipRequestInterceptor　chain.request().toString():" + chain.request().toString())
                            val request = chain.request()
                                    .newBuilder()
                                    .header("Content-Encoding", "gzip")
                                    .build()
                            // Log.i(TAG, "GzipRequestInterceptor　request.toString():" + request.toString())
                            chain.proceed(request)
                        }
                        .build())
                .build().create(UploadService::class.java)
        val result = uploadService.uploadLogs(logContents.deviceId, logContents).execute()
        Log.d(LOG, "upload: success=${result.isSuccessful}, error=${result.errorBody()}")
        Pair("upload successful: ${result.isSuccessful} (see logs if failed)", true)
    } catch (e: Exception) {
        Log.d(LOG, "Couldn't upload: ${e.localizedMessage}")
        Pair("upload failed: $e", false)
    }
    launch(UI) { Toast.makeText(context, messageToUser, Toast.LENGTH_LONG).show() }
    return success
}


fun gatherUploadAndDeleteLogs(context: Context): Boolean {
    val db = Room
            .databaseBuilder(context, BrocoliServiceDatabase::class.java, NetworkService.NS_DB_NAME)
            .fallbackToDestructiveMigration()
            .allowMainThreadQueries()
            .build()
    val logRepository = NeighborhoodWatcherLogImplementation(db.logEventDao())
    val logs = logRepository.getCompleteLogAsList()
    val content = LogContents(
            context.getSharedPreferences(NetworkService.PREFS, Context.MODE_PRIVATE).getString(NetworkService.EXTRA_DEVICE_ID, "notset"),
            Date().time,
            logs.map { convertLogEventToDbLogEvent(it) },
            context.getSharedPreferences(NearbyConnectivity.SHARED_PREFS_NAME, Context.MODE_PRIVATE).getInt(NearbyConnectivity.BYTES_SENT_COUNT, 0),
            context.getSharedPreferences(NearbyConnectivity.SHARED_PREFS_NAME, Context.MODE_PRIVATE).getInt(NearbyConnectivity.BYTES_SENT_COUNT, 0),
            Build.MANUFACTURER + " / " + Build.PRODUCT + " / " + Build.MODEL + " / " + Build.BOARD + " (${Build.DEVICE})",
            Build.VERSION.RELEASE + " (${Build.VERSION.SDK_INT})",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                btManager.adapter.bluetoothLeAdvertiser != null
            } else {
                false
            },
            db.locationUpdateDao().getAll()
    )
    val success = uploadLog(context, content)
    if (success) {
        logRepository.clearLog()
        context.getSharedPreferences(NearbyConnectivity.SHARED_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(NearbyConnectivity.BYTES_RECEIVED_COUNT)
                .remove(NearbyConnectivity.BYTES_SENT_COUNT)
                .apply()
    }
    return success
}

interface UploadService {
    @POST("uploadName/{deviceId}")
    fun uploadLogs(@Path("deviceId") deviceId: String, @Body logContents: LogContents): Call<Unit>
}

class LogUploadJobService : JobService() {
    companion object {
        private const val NOTIFICATION_ID = 85
        private const val LOG_NOTIFICATION_CHANNEL = "log_upload"
    }
    private var params: JobParameters? = null
    private var largeTask: JobUploadTask? = null

    override fun onStartJob(params: JobParameters): Boolean {
        // get param to use if if needed ...
        this.params = params
        largeTask = JobUploadTask()
        largeTask!!.execute()
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (largeTask != null)
            largeTask!!.cancel(true)

        return false
    }

    private inner class JobUploadTask : AsyncTask<Void, Void, Void>() {
        var uploadSuccessFull = false
        override fun onPostExecute(aVoid: Void?) {
            jobFinished(params, !uploadSuccessFull)
            Log.d(JobUploadTask::class.java.simpleName, "Logs were uploaded: $uploadSuccessFull")
            super.onPostExecute(aVoid)
        }

        override fun doInBackground(vararg params: Void?): Void? {
            uploadSuccessFull = gatherUploadAndDeleteLogs(this@LogUploadJobService)
            if (uploadSuccessFull) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val name = LOG_NOTIFICATION_CHANNEL
                    val description = getString(R.string.log_channel_description)
                    val importance = NotificationManager.IMPORTANCE_DEFAULT
                    val channel = NotificationChannel(LOG_NOTIFICATION_CHANNEL, name, importance)
                    channel.description = description
                    // Register the channel with the system; you can't change the importance
                    // or other notification behaviors after this
                    val notificationManager = getSystemService(NotificationManager::class.java)
                    notificationManager.createNotificationChannel(channel)
                }


                val notification = NotificationCompat.Builder(this@LogUploadJobService, LOG_NOTIFICATION_CHANNEL)
                        .setSmallIcon(R.drawable.ic_cloud_done)
                        .setContentTitle(applicationContext.getString(R.string.title_log_upload_done_short))
                        .setContentText(applicationContext.getString(R.string.title_log_upload_done_long))
                        .setStyle(NotificationCompat.BigTextStyle()
                                .bigText(applicationContext.getString(R.string.title_log_upload_done_long)))

                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .build()
                NotificationManagerCompat.from(this@LogUploadJobService).notify(NOTIFICATION_ID, notification)
                Log.d(JobUploadTask::class.java.simpleName, "there should be a notification now!")
            }
            return null
        }
    }

}
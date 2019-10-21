package de.upb.cs.brocolitestapp

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import com.github.salomonbrys.kodein.instance
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import de.upb.cs.brocoli.database.LocationUpdateDao
import de.upb.cs.brocoli.library.*
import de.upb.cs.brocoli.ui.activities.DebugActivity
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    companion object {
        private val TAG = MainActivity::class.java.simpleName
        const val SERVICE_ID: ServiceId = 123
        private const val PERMISSION_REQUEST = 1
        private const val REQUEST_CHECK_SETTINGS = 555
        private const val NAME_PREFERENCE = "user id"
    }

    private var ownId: UserID? = null
    private val chatMessageDao: ChatMessageDao = ServiceStarter.starter.chatMessageDao
    private var firstScrollDown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkPermissions()
        checkAndSetName()

        startServiceClicked(null)
        startAdHocClicked(null)

        Log.d(TAG, "Found ${chatMessageDao.count()} messages in repository")

        val timeFormatter = SimpleDateFormat("HH:mm")

        chatMessageDao.getAll().observe(this, android.arch.lifecycle.Observer<List<ChatMessage>> { list ->
            val list = list?.sortedBy { it.time } ?: listOf()
            val text = list.joinToString("\n") { "(${timeFormatter.format(Date(it.time))}) ${it.from}: ${it.content}" }
            chatTextView.text = text
            if (!firstScrollDown) {
                chatScrollView.fullScroll(View.FOCUS_DOWN)
                firstScrollDown = true
            }
        })
        chatScrollView.fullScroll(View.FOCUS_DOWN)

        button2.setOnClickListener {
            val intent = Intent(this, DebugActivity::class.java)
            startActivity(intent)
        }


        // this is done to resolve background stuff that happens when gms cannot get location
        val locationRequest = LocationRequest().apply {
            interval = 10_000
            fastestInterval = 5_000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        LocationServices.getSettingsClient(this).checkLocationSettings(builder.build()).addOnSuccessListener {
            Log.d(TAG, "Received: $it")
        }.addOnFailureListener { exception ->
            Log.e(TAG, "couldn't start", exception)
            if (exception is ResolvableApiException){
                // Location settings are not satisfied, but this can be fixed
                // by showing the user a dialog.
                try {
                    // Show the dialog by calling startResolutionForResult(),
                    // and check the result in onActivityResult().
                    exception.startResolutionForResult(this@MainActivity,
                            REQUEST_CHECK_SETTINGS)
                } catch (sendEx: IntentSender.SendIntentException) {
                    // Ignore the error.
                }
            }

        }
    }

    private fun checkPermissions() {
        val neededPermissions = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.CHANGE_WIFI_STATE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.CHANGE_NETWORK_STATE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.ACCESS_WIFI_STATE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.BLUETOOTH)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (!neededPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    neededPermissions.toTypedArray(), PERMISSION_REQUEST)
        }
    }

    fun startServiceClicked(view: View?) {
        val x = ownId
        if (x == null) {
            Toast.makeText(this@MainActivity, "The deviceId is not set. This shouldn't happen. Restart the app.", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, de.upb.cs.brocoli.library.NetworkService::class.java)
        intent.putExtra(NetworkService.EXTRA_DEVICE_ID, x.id)
        intent.putExtra(NetworkService.EXTRA_NETWORK_ID, "de.upb.cs.brocoli.ad-hoc.servercommunicationtest")
        intent.putExtra(NetworkService.EXTRA_CALLBACK_CLASS, ServiceStarter::class.java.name)
        intent.putExtra(NetworkService.EXTRA_MASTER_SERVER_ADDRESS, "brocoli.example.com")
        intent.putExtra(NetworkService.EXTRA_MASTER_SERVER_PORT, 9099)
        startService(intent)

        ServiceStarter.starter.connect()
        launch {
            delay(2000)
            val dao = ServiceStarter.starter.serviceBinding?.service?.getKodein()?.instance<LocationUpdateDao>()
            dao?.getLastLive()?.observe(this@MainActivity, android.arch.lifecycle.Observer {
                buttonStopService.text = "Last GPS from ${Date(it?.timestamp ?: 0)}"
            })
        }
    }

    fun startAdHocClicked(view: View?) {
        ServiceStarter.starter.startRobustCommunication()
    }

    fun sendMessageClicked(view: View) {
        val binding = ServiceStarter.starter.serviceBinding
        if (binding != null && binding.isBinderAlive) {
            val input = chatTextEditText.text.toString()
            if (input.isEmpty())
                return
            val messageBody: Array<Byte> = input.toByteArray().toTypedArray()
            chatTextEditText.text.clear()
            val message = BrocoliMessage(ownId!!, UserID(BROADCAST_USER_ID), SERVICE_ID, ttlHours = 4, priority = BrocoliPriority.Low, messageBody = messageBody)
            binding.service.sendMessage(message, false)
            chatMessageDao.addMessage(convertBrocoliMessageToChatMessage(message))
            chatScrollView.fullScroll(View.FOCUS_DOWN)
            Log.d(TAG, "gave message (id: ${message.id}) to the service")
        } else {
            Toast.makeText(this, "Error: service not available", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopAdHocClicked(view: View?) {
        ServiceStarter.starter.stopRobustCommunication()
    }

    fun stopServiceClicked(view: View?) {
        stopAdHocClicked(view)
        if (ServiceStarter.starter.serviceConnection != null)
            unbindService(ServiceStarter.starter.serviceConnection)
        stopService(Intent(this, de.upb.cs.brocoli.library.NetworkService::class.java))
    }

    override fun onStop() {
        Log.d(TAG, "onStop ${ServiceStarter.starter.serviceBinding?.service}")
        ServiceStarter.starter.serviceBinding?.service?.restartAllowed = true
        super.onStop()
    }

    override fun onResume() {
        Log.d(TAG, "onResume ${ServiceStarter.starter.serviceBinding?.service}")
        ServiceStarter.starter.serviceBinding?.service?.restartAllowed = false
        super.onResume()
    }

    override fun onStart() {
        Log.d(TAG, "onStart ${ServiceStarter.starter.serviceBinding?.service}")
        ServiceStarter.starter.serviceBinding?.service?.restartAllowed = false
        super.onStart()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.mainmenu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.startAdHoc -> {
                startAdHocClicked(null)
            }
            R.id.stopAdHoc -> {
                stopAdHocClicked(null)
            }
            R.id.uploadLogs -> {
                if (ServiceStarter.starter.serviceBinding != null)
                    ServiceStarter.starter.serviceBinding?.service?.uploadLogs()
                else {
                    Toast.makeText(this, "Ad hoc is not running", Toast.LENGTH_SHORT).show()
                }
            }
            R.id.clearAll -> {
                if (ServiceStarter.starter.serviceBinding != null) {
                    ServiceStarter.starter.serviceBinding?.service?.clearLogs()
                    ServiceStarter.starter.serviceBinding?.service?.clearMessageRepository()
                    chatMessageDao.clear()
                } else {
                    Toast.makeText(this, "Ad hoc is not running", Toast.LENGTH_SHORT).show()
                }
                stopAdHocClicked(null)
                stopServiceClicked(null)
                getPreferences(Context.MODE_PRIVATE).edit().remove(NAME_PREFERENCE).commit()
                System.exit(0)
            }
            R.id.showNeighborsMenu -> {
                showNeighbors()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun checkAndSetName() {
        if (ownId == null) {
            if (getPreferences(Context.MODE_PRIVATE).contains(NAME_PREFERENCE)) {
                val name = getPreferences(Context.MODE_PRIVATE).getString(NAME_PREFERENCE, "")
                val id = UserID(name)
                ownId = id
                return
            }

            val builder = AlertDialog.Builder(this)
            builder.setTitle("Enter a user name (no special characters or spaces)")
            val input = EditText(this)
            builder.setView(input)
            builder.setPositiveButton("OK") { _, _ ->
                val name = input.text.toString()
                try {
                    val id = UserID(name)
                    ownId = id
                    getPreferences(Context.MODE_PRIVATE).edit().putString(NAME_PREFERENCE, name).apply()
                    startServiceClicked(null)
                    startAdHocClicked(null)
                } catch (e: Exception) {
                    checkAndSetName()
                }
            }
            builder.setNegativeButton("Quit") { _, _ ->
                System.exit(0)
            }
            builder.show()
        }
    }

    private fun showNeighbors() {
        val connectivity = ServiceStarter.starter.serviceBinding?.service?.getConnectivity() ?: return
        val neighbors = connectivity.getNeighbors()
        val connected = connectivity.getConnectedNeighbors()
        val message = "Visible:\n" +
                "${neighbors.joinToString("\n") { it.id }}\n" +
                "Connected:\n" +
                connected.joinToString("\n") { it.id }
        AlertDialog.Builder(this).setMessage(message)
                .setPositiveButton("OK") { _, _ -> }
                .show()
    }
}

fun convertBrocoliMessageToChatMessage(brocoliMessage: BrocoliMessage): ChatMessage =
        ChatMessage(brocoliMessage.id, brocoliMessage.from.id, brocoliMessage.timestamp, String(brocoliMessage.messageBody.toByteArray()))

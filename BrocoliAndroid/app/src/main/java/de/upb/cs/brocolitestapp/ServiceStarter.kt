package de.upb.cs.brocolitestapp

import android.app.Notification
import android.app.PendingIntent
import android.arch.persistence.room.Room
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import de.upb.cs.brocoli.library.*

class ServiceStarter {
    companion object {
        @JvmStatic
        val starter: ServiceStarter = ServiceStarter()

        @JvmStatic
        fun getInstance() = starter

        private val TAG = ServiceStarter::class.java.simpleName
    }

    val appContext = MyApp.instance.applicationContext
    var serviceBinding: NetworkService.NetworkServiceBinder? = null
    var serviceConnection: ServiceConnection? = null
    val chatMessageDao: ChatMessageDao = Room
            .databaseBuilder(appContext, ChatMessageDatabase::class.java, "chat-db")
            .allowMainThreadQueries()
            .build().chatMessageDao()

    fun connect() {
        Log.d(TAG, "Connecting to service...")
        serviceConnection = object : ServiceConnection {
            private val tag = "ServiceConnection"

            override fun onServiceConnected(className: ComponentName,
                                            service: IBinder) {
                Log.d(TAG, "ServiceBinding is set to $service")
                serviceBinding = service as NetworkService.NetworkServiceBinder
                serviceBinding?.service?.restartAllowed = false
                serviceBinding?.service?.registerService(MainActivity.SERVICE_ID,
                        object : ServiceCallback {
                            override fun messageArrived(message: BrocoliMessage) {
                                chatMessageDao.addMessage(convertBrocoliMessageToChatMessage(message))
                            }
                        },
                        "MainActivityDummyService",
                        object : ServiceOnlineHandler {
                            override fun fetchMessagesFromServer(): List<BrocoliMessage> {
                                return listOf()
                            }

                            override fun handleMessage(message: BrocoliMessage): BrocoliMessage? {
                                return null
                            }

                            override fun willHandleMessages(): Boolean = false
                        },
                        null,
                        true)
            }

            override fun onServiceDisconnected(arg0: ComponentName) {
                Log.d(tag, "service disconnected")
            }
        }
        appContext.bindService(Intent(appContext, NetworkService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun startRobustCommunication() {
        Log.d(TAG, "Starting robust communication on $serviceBinding")
        serviceBinding?.service?.startAdHoc(createNotification(), ServiceStarter::class.java.name, false)
        // serviceBinding?.service?.logLocations = true
    }

    fun stopRobustCommunication() {
        serviceBinding?.service?.stopAdHocGracefully()
        // serviceBinding?.service?.logLocations = false
    }

    /**
     * Show a notification while this service is running.
     */
    private fun createNotification(): Notification {
        // In this sample, we'll use the same text for the ticker and the expanded notification
        val text = appContext.getText(de.upb.cs.brocolitestapp.R.string.service_notification_label)

        // The PendingIntent to launch our activity if the user selects this notification
        val contentIntent = PendingIntent.getActivity(appContext, 0,
                Intent(appContext, MainActivity::class.java), 0)

        // Set the info for the views that show in the notification panel.
        return Notification.Builder(appContext)
                .setSmallIcon(de.upb.cs.brocoli.R.drawable.notification_icon_background)  // the status icon
                .setTicker(text)  // the status text
                .setWhen(System.currentTimeMillis())  // the time stamp
                .setContentTitle(appContext.getText(de.upb.cs.brocoli.R.string.service_label))  // the label of the entry
                .setContentText(text)  // the contents of the entry
                .setContentIntent(contentIntent)  // The intent to send when the entry is clicked
                .build()
    }
}

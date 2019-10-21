package de.upb.cs.brocoli.library

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.arch.persistence.room.Room
import android.content.*
import android.net.ConnectivityManager
import android.net.ConnectivityManager.CONNECTIVITY_ACTION
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v7.app.AppCompatActivity
import android.util.Log
import com.github.salomonbrys.kodein.*
import com.google.android.gms.nearby.connection.Strategy
import com.google.gson.Gson
import de.upb.cs.brocoli.R
import de.upb.cs.brocoli.connectivity.Connectivity
import de.upb.cs.brocoli.connectivity.JsonMessageSerializer
import de.upb.cs.brocoli.connectivity.NearbyConnectivity
import de.upb.cs.brocoli.database.*
import de.upb.cs.brocoli.location.LocationCrawler
import de.upb.cs.brocoli.model.*
import de.upb.cs.brocoli.neighborhoodwatch.*
import de.upb.cs.brocoli.ui.NeighborhoodWatcherLogReader
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.timer

class NetworkService : Service(), ServiceHandler {
    companion object {
        private val tag = NetworkService::class.java.simpleName
        private const val WAKE_LOCK_TAG = "NetworkService:WakeLockTag"
        const val PREFS = "default-network-service-prefs"
        /**
         * The (unique!) ID the device will have in the ad hoc network
         */
        const val EXTRA_DEVICE_ID = "NetworkServiceDeviceIdExtra"
        /**
         * The network ID. Theoretically, more than one network can be run at a time on a device by
         * different apps.
         */
        const val EXTRA_NETWORK_ID = "NetworkServiceNetworkIdExtra"
        /**
         * A valid socket address, either an IP or host name
         */
        const val EXTRA_MASTER_SERVER_ADDRESS = "MasterServerAddress"
        /**
         * A valid socket port
         */
        const val EXTRA_MASTER_SERVER_PORT = "MasterServerPort"
        /**
         * The full class specified with this key in the starting intent is the one that gets
         * initialized if the service was restarted. It needs to have a static method "getInstance()"
         * which retrieves an instance that has a method "connect()". This method should bind to the
         * [NetworkService] and, upon success, register all callbacks.
         */
        const val EXTRA_CALLBACK_CLASS = "NetworkServiceCallbackClass"
        const val EXTRA_RESTART_ADHOC_FLAG = "NetworkServiceRestartAdhocExtra"
        const val NS_DB_NAME = "default"
        const val exchangeWithServerMaxTime = 120_000L
        const val RESTART_PERIOD = 15 * 60_000L
        const val RESTART_OFFSET = 5_000
        const val SAVED_REGISTRATIONS = "NetworkService:SavedRegistrations"
        const val SAVED_STARTER = "NetworkService:SavedStarter"
    }

    private val gson = Gson()
    private var mServiceLooper: Looper? = null
    private lateinit var kodein: Kodein
    private lateinit var algorithmMessageRepository: AlgorithmMessageRepository
    private var systemWakeLock: PowerManager.WakeLock? = null
    private val serviceCallbacks = ConcurrentHashMap<Byte, Pair<ServiceCallback, String?>>()
    private val serviceOnlineHandlers = ConcurrentHashMap<Byte, ServiceOnlineHandler>()
    private val serviceOnlineHandlerIDs = ConcurrentHashMap<Byte, UserID?>()
    private val serviceIdWhitelist = mutableListOf<ServiceId>()

    private lateinit var logWriter: NeighborhoodWatcherLog
    private fun log(event: LogEvent) {
        logWriter.addLogEntry(event)
        Log.d(tag, "Event: $event")
    }

    /**
     * This is used for testing and experiments. When set, the service will not call
     * [ServiceOnlineHandler.handleMessage], unless the device is connected or within reach
     * of a WiFi with SSID [fakeOnlineWifiSsid].
     */
    var suppressOnlineActivity: Boolean = false

    /**
     * If [suppressOnlineActivity] is true, the [NetworkService] will call make use of [ServiceOnlineHandler]s
     * only if a WiFi with this SSID is within reach of the device.
     */
    var fakeOnlineWifiSsid = ""

    private lateinit var wifiManager: WifiManager

    private val onlineStateBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(broadcastContext: Context?, broadcastIntent: Intent?) {
            if (broadcastIntent != null && broadcastIntent.action == CONNECTIVITY_ACTION) {
                checkOnlineAndExchangeWithServer()
            }
        }
    }
    private val currentlyExchangingWithServer = AtomicBoolean(false)
    private var lastExchangeWithServerStarted = 0L

    private var restartTimer: Timer? = null

    /**
     * The [NetworkService] automatically restarts the app (using the initializingClass parameter of
     * various methods) if running in ad hoc mode, to circumvent some problems with the network layer.
     * If an app wants to prevent that, it can set this property to false. This can be used e.g. to
     * disallow restarts while the app is in the foreground.
     */
    var restartAllowed = true
        set(value) {
            field = value
            Log.d(tag, "RestartAllowed: $value")
            if (!value) {
                // in case the restart is forbidden, set it to forbidden again a little time later,
                // because setting it by Activity.onStop() sometimes happens after Activity.onStart()
                // is called. Thus, (de-)activating this by visibility of Activities doesn't work
                // otherwise.
                launch {
                    delay(1000)
                    field = false
                }
            }
        }
    private var classForCallbackRegistrations: String? = null
    private var startAdHocCallerClass: String? = null

    var adHocRunning = false
        private set

    private lateinit var ownDeviceID: UserID

    // Unique Identification Number for the Notification.
    // We use it on Notification start, and to cancel it.
    private val NOTIFICATION = R.string.local_service_running_notification_id

    private lateinit var androidConnectivityManager: ConnectivityManager

    private lateinit var locationCrawler: LocationCrawler
    var logLocations = false
        set(value) {
            if (value && value != logLocations) {
                locationCrawler.start()
            } else {
                locationCrawler.stop()
            }
            field = value
        }

    inner class NetworkServiceBinder : Binder() {
        val service: NetworkService
            get() = this@NetworkService
    }

    override fun onUnbind(intent: Intent?): Boolean {
//        return super.onUnbind(intent)
        return false
    }

    override fun onCreate() {
        androidConnectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceId = (if (intent != null) {
            val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                    ?: throw IllegalArgumentException("The EXTRA_DEVICE_ID extra has to contain a valid device Id, when starting this service")
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(EXTRA_DEVICE_ID, deviceId).apply()
            deviceId
        } else {
            // the service is maybe restarted after being killed, thus doesn't get the intent
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(EXTRA_DEVICE_ID, null)
        })
                ?: throw IllegalStateException("The service could not be started, because there is neither a valid device ID in the intent nor one available in the internal preferences from a previous start.")

        ownDeviceID = UserID(deviceId)

        val adHocServiceId = (if (intent != null) {
            val networkId = intent.getStringExtra(EXTRA_NETWORK_ID)
                    ?: throw IllegalArgumentException("The EXTRA_NETWORK_ID extra has to contain a valid network Id, when starting this service")
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(EXTRA_NETWORK_ID, networkId).apply()
            networkId
        } else {
            // the service is maybe restarted after being killed, thus doesn't get the intent
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(EXTRA_NETWORK_ID, null)
        })
                ?: throw IllegalStateException("The service could not be started, because there is neither a valid network ID in the intent nor one available in the internal preferences from a previous start.")

        val masterServerAddress = (if (intent != null) {
            val serverAddress = intent.getStringExtra(EXTRA_MASTER_SERVER_ADDRESS)
                    ?: throw IllegalArgumentException("The EXTRA_MASTER_SERVER_ADDRESS extra has to contain a valid server address, when starting this service")
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(EXTRA_MASTER_SERVER_ADDRESS, serverAddress).apply()
            serverAddress
        } else {
            // the service is maybe restarted after being killed, thus doesn't get the intent
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(EXTRA_MASTER_SERVER_ADDRESS, null)
        })
                ?: throw IllegalStateException("The service could not be started, because there is neither a valid EXTRA_MASTER_SERVER_ADDRESS in the intent nor one available in the internal preferences from a previous start.")

        val masterServerPort = (if (intent != null) {
            val serverPort = intent.getIntExtra(EXTRA_MASTER_SERVER_PORT, -1)
            if (serverPort < 1)
                throw IllegalArgumentException("The EXTRA_MASTER_SERVER_PORT extra has to contain a valid network port, when starting this service")
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(EXTRA_MASTER_SERVER_PORT, serverPort).apply()
            serverPort
        } else {
            // the service is maybe restarted after being killed, thus doesn't get the intent
            val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(EXTRA_MASTER_SERVER_PORT, -1)
            if (p < 1)
                throw IllegalStateException("The service could not be started, because there is neither a valid EXTRA_MASTER_SERVER_PORT in the intent nor one available in the internal preferences from a previous start.")
            p
        })

        val repositoryModule = Kodein.Module {
            val db = Room
                    .databaseBuilder(this@NetworkService.applicationContext, BrocoliServiceDatabase::class.java, NS_DB_NAME)
                    .fallbackToDestructiveMigration()
                    .allowMainThreadQueries()
                    .build()
            val logRepository = NeighborhoodWatcherLogImplementation(db.logEventDao())
            bind<LocationUpdateDao>() with singleton { db.locationUpdateDao() }
            bind<NeighborhoodWatcherLog>() with singleton { logRepository }
            bind<NeighborhoodWatcherLogReader>() with singleton { logRepository }
            bind<AlgorithmMessageRepository>() with singleton { AlgorithmMessageRepositoryImplementation(db.brocoliMessageDao(), ::newMessageCallback) }
            bind<AckRepository>() with singleton { AcknowledgementsRepositoryImplementation(db.ackMessageDao()) }
        }

        val connectivityModule = Kodein.Module {
            bind<MessageSerializer>() with singleton { JsonMessageSerializer() }
            bind<Strategy>() with singleton { Strategy.P2P_CLUSTER }
            bind<NearbyConnectivity>() with singleton { NearbyConnectivity(kodein, ownDeviceID, adHocServiceId, 60000, 30000) }
            bind<Connectivity>() with singleton { kodein.instance<NearbyConnectivity>() }
        }

        val neighborhoodWatcherConfigurationModule = Kodein.Module {
            bind<Random>() with singleton { Random(0) }
            bind<Long>(NEIGHBORHOOD_WATCHER_TIMER_INTERVAL) with singleton { 30000L }
            bind<MessageRouterFactory>() with singleton { AcknowledgingMessageRouterFactory() }
        }

        val robustServerCommunicationModule = Kodein.Module {
            constant(ServerCommunication.MASTER_SERVER_USER_ID) with "rkserver"
            constant(ServerCommunication.REFRESH_INTERVAL_SECONDS) with 30
            constant(ServerCommunication.MASTER_SERVER_ADDRESS_FIELD) with masterServerAddress
            constant(ServerCommunication.MASTER_SERVER_PORT_FIELD) with masterServerPort
        }

        kodein = Kodein {
            import(repositoryModule)
            import(connectivityModule)
            import(neighborhoodWatcherConfigurationModule)
            import(robustServerCommunicationModule)
            bind<Context>() with singleton { this@NetworkService.applicationContext }
            bind<NeighborhoodWatcher>() with singleton { NeighborhoodWatcherImplementation(kodein, ownDeviceID) }
            bind<ServerCommunication>() with singleton { ServerCommunication(kodein, ownDeviceID, serviceIdWhitelist, this@NetworkService) }
        }

        algorithmMessageRepository = kodein.instance()
        val currentTime = Date().time
        val oldMessages = algorithmMessageRepository.getAllMessagesAsList().filter { currentTime > it.timestamp + it.ttlHours * 3600_000 }.map { it.id }
        algorithmMessageRepository.deleteByIds(oldMessages)

        val ackRepository = kodein.instance<AckRepository>()
        ackRepository.deleteByExpiryDate(currentTime)

        registerReceiver(onlineStateBroadcastReceiver, IntentFilter(CONNECTIVITY_ACTION))

        if (intent != null) {
            if (intent.getBooleanExtra(EXTRA_RESTART_ADHOC_FLAG, false)) {
                launch {
                    delay(1000)
                    reloadSavedState(intent)
                }
            } else {
                classForCallbackRegistrations = intent.getStringExtra(EXTRA_CALLBACK_CLASS)
                        ?: throw java.lang.IllegalArgumentException("No 'EXTRA_CALLBACK_CLASS' key provided.")
            }
        }
        locationCrawler = LocationCrawler(kodein)
        logWriter = kodein.instance()
        // If we get killed, after returning from here, restart
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(tag, "Service thread is ${Thread.currentThread()?.id}")
        return NetworkServiceBinder()
    }

    override fun onDestroy() {
        mServiceLooper?.thread?.interrupt()
        Log.d(tag, "stopped")
        unregisterReceiver(onlineStateBroadcastReceiver)
    }

    /**
     * Starts the ad hoc network.
     *
     * The service will start running as a foreground service to not be killed by the system. There-
     * fore the [notification] is necessary. It should provide a user with information about the net-
     * work and enable to stop the network or lead to an activity that holds the appropriate controls.
     * The [initializingClass] is the name of the class that holds an instance of itself, retrievable
     * by "x.getInstance()", which has a method "startRobustCommunication()".
     */
    fun startAdHoc(notification: Notification, initializingClass: String, restartAllowed: Boolean = true) {
        Log.d(tag, "starting ad hoc mode")
        startForeground(NOTIFICATION, notification)
        systemWakeLock?.release()
        systemWakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            this.acquire(RESTART_PERIOD * 2)
        }

        adHocRunning = true

        kodein.instance<NeighborhoodWatcher>().start()
        kodein.instance<ServerCommunication>().start()
        scheduleLogUpload()
        restartTimer = createRestartTimer()
        startAdHocCallerClass = initializingClass
        this.restartAllowed = restartAllowed
    }

    /**
     * Stops the ad hoc network
     */
    fun stopAdHocGracefully() {
        Log.d(tag, "stopping ad hoc gracefully")
        mServiceLooper?.quit()
        kodein.instance<NeighborhoodWatcher>().stop()
        kodein.instance<ServerCommunication>().stop()
        stopForeground(true)
        systemWakeLock?.release()
        systemWakeLock = null
        adHocRunning = false
        Log.d(tag, "collected ${algorithmMessageRepository.getAllMessagesAsList().size} messages.")
        restartTimer?.cancel()
        restartTimer = null
    }

    /**
     * Registers a [ServiceCallback] for a service id. Any new messages arriving with that specific
     * service id will be passed to that ServiceCallback.
     */
    override fun registerService(serviceId: Byte, callbackHandler: ServiceCallback, serviceName: String?,
                                 serviceOnlineHandler: ServiceOnlineHandler, onlineHandlerId: UserID?,
                                 shouldExchangeWithServer: Boolean) {
        serviceCallbacks[serviceId] = Pair(callbackHandler, serviceName)
        serviceOnlineHandlers[serviceId] = serviceOnlineHandler
        if (onlineHandlerId != null)
            serviceOnlineHandlerIDs[serviceId] = onlineHandlerId
        if (shouldExchangeWithServer && !serviceIdWhitelist.contains(serviceId))
            serviceIdWhitelist.add(serviceId)
    }

    /**
     * Removes all references to callbacks of the given service id
     */
    override fun unregisterService(serviceId: ServiceId) {
        serviceCallbacks.remove(serviceId)
        serviceOnlineHandlers.remove(serviceId)
        serviceOnlineHandlerIDs.remove(serviceId)
    }

    fun getServiceNameFromServiceID(serviceId: ServiceId): String? {
        val serviceName = serviceCallbacks[serviceId]
        return serviceName?.second
    }

    /**
     * Sends a message to the specified user. If the recipient is available online, it will be sent
     * using online services, otherwise it will be stored and sent using the best available option
     * coming up in the future.
     */
    @Throws(OnlineCommunicationException::class)
    override fun sendMessage(message: BrocoliMessage, sendOnlineOnly: Boolean): BrocoliMessage? {
        val handler = serviceOnlineHandlers[message.serviceId]
                ?: throw IllegalArgumentException("There is no handler registered for the service this message originates from.")
        val isOnline = isOnline()
        return if (handler.willHandleMessages() && isOnline) {
            handler.handleMessage(message)
        } else {
            Log.d(tag, "Couldn't send message online (handler: $handler, isOnline: ${isOnline()})")
            if (!sendOnlineOnly) {
                algorithmMessageRepository.add(brocoliMessageToAlgorithmMessage(message))
                val messageCreationInfo = MessageCreationInfo(message.serviceId, message.ttlHours, message.priority.toString(), message.messageBody.size)
                log(MessageEvent(MessageEvent.Type.MessageCreated, message.from.id, message.to.id, AlgorithmContentMessage::class.java.simpleName,
                        message.id, creationInfo = messageCreationInfo))
                null
            } else {
                throw OnlineCommunicationException("You are not online.")
            }
        }
    }

    private fun newMessageCallback(message: AlgorithmContentMessage) {
        launch {
            // check if the target ID is either this device ID or a broadcast (to be specified!)
            if (message.fromId != ownDeviceID.id && (message.toId == ownDeviceID.id || message.toId == BROADCAST_USER_ID)) {
                notifyUpperLayer(algorithmMessageToBrocoliMessage(message))
            }

            if (isOnline() &&
                    UserID(message.toId) == serviceOnlineHandlerIDs[message.serviceId] &&
                    serviceOnlineHandlers[message.serviceId]?.willHandleMessages() == true) {
                val ackRepository = kodein.instanceOrNull<AckRepository>()
                try {
                    val resultMessage = serviceOnlineHandlers[message.serviceId]?.handleMessage(algorithmMessageToBrocoliMessage(message))
                    if (resultMessage != null) {
                        if (resultMessage.to == ownDeviceID || message.toId == BROADCAST_USER_ID) {
                            notifyUpperLayer(resultMessage)
                        }
                        if (resultMessage.to != ownDeviceID) {
                            algorithmMessageRepository.add(brocoliMessageToAlgorithmMessage(resultMessage))
                            Log.d(tag, "Got a message that is for someone else as an answer. Giving it to the ad hoc network.")
                        }
                    }
                    ackRepository?.add(Ack(message.id, message.timestamp + message.ttlHours * 3_600_000))
                    algorithmMessageRepository.deleteByIds(listOf(message.id))
                } catch (e: OnlineCommunicationException) {
                    Log.d(tag, "Failed to upload message ${e.localizedMessage}")
                }
            }
        }
    }

    private fun notifyUpperLayer(message: BrocoliMessage) {
        val serviceCallback = serviceCallbacks[message.serviceId]
        if (serviceCallback != null) {
            serviceCallback.first.messageArrived(message)
        } else {
            Log.e(tag, "Received a message for service id '${message.serviceId}', which is not known. The message is discarded.")
        }
    }

    fun uploadLogs() {
        launch {
            gatherUploadAndDeleteLogs(this@NetworkService)
        }
    }

    fun clearLogs() {
        kodein.instance<NeighborhoodWatcherLog>().clearLog()
        getSharedPreferences(NearbyConnectivity.SHARED_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(NearbyConnectivity.BYTES_RECEIVED_COUNT)
                .remove(NearbyConnectivity.BYTES_SENT_COUNT)
                .apply()
    }

    fun clearMessageRepository() {
        kodein.instance<AlgorithmMessageRepository>().deleteAll()
    }

    fun getConnectivity(): NearbyConnectivity = kodein.instance()

    override fun fetchMessagesFromServer(serviceId: Byte) {
        launch {
            serviceOnlineHandlers.values.forEach {
                it.fetchMessagesFromServer().forEach { algorithmMessageRepository.add(brocoliMessageToAlgorithmMessage(it)) }
            }
        }
    }

    internal fun isOnline(): Boolean {
        val isReallyOnline = androidConnectivityManager.activeNetworkInfo?.isConnected ?: false
        val fakeOnlineOverWifi = isReallyOnline && suppressOnlineActivity && isConnected(fakeOnlineWifiSsid)
        return if (suppressOnlineActivity) fakeOnlineOverWifi else isReallyOnline
    }

    private fun checkOnlineAndExchangeWithServer() {
        launch {
            Log.d(tag, "checkOnlineAndExchangeWithServer()")
            if (currentlyExchangingWithServer.getAndSet(true) && (System.currentTimeMillis() - lastExchangeWithServerStarted) < exchangeWithServerMaxTime)
                return@launch

            lastExchangeWithServerStarted = System.currentTimeMillis()
            try {
                if (isOnline()) {
                    val ackRepository = kodein.instanceOrNull<AckRepository>()
                    algorithmMessageRepository.getAllMessagesAsList().filter {
                        it.id !in (ackRepository?.getAcknowledgementsAsList()
                                ?: listOf()).map { it.id }
                    }.forEach { message ->
                        if (UserID(message.toId) == serviceOnlineHandlerIDs[message.serviceId] &&
                                serviceOnlineHandlers[message.serviceId]?.willHandleMessages() == true) {
                            try {
                                val resultMessage = serviceOnlineHandlers[message.serviceId]?.handleMessage(algorithmMessageToBrocoliMessage(message))
                                if (resultMessage != null) {
                                    if (resultMessage.to == ownDeviceID || message.toId == BROADCAST_USER_ID) {
                                        notifyUpperLayer(resultMessage)
                                    }
                                    if (resultMessage.to != ownDeviceID) {
                                        algorithmMessageRepository.add(brocoliMessageToAlgorithmMessage(resultMessage))
                                        Log.d(tag, "Got a message that is for someone else as an answer. Giving it to the ad hoc network.")
                                    }
                                }
                                ackRepository?.add(Ack(message.id, message.timestamp + message.ttlHours * 3_600_000))
                                algorithmMessageRepository.deleteByIds(listOf(message.id))
                            } catch (_: OnlineCommunicationException) {
                            }
                        }
                    }
                    fetchMessagesFromServer(0)
                }
                currentlyExchangingWithServer.set(false)
            } catch (e: Exception) {
                currentlyExchangingWithServer.set(false)
                throw e
            }
        }
    }

    private fun scheduleLogUpload() {
        val componentName = ComponentName(applicationContext, LogUploadJobService::class.java)
        val jobInfo = JobInfo.Builder(1, componentName)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresCharging(true)
                .setPersisted(true)
                .build()
        val jobScheduler = applicationContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        jobScheduler.schedule(jobInfo)
        Log.d(tag, "Scheduled log upload")
    }

    private fun createRestartTimer(): Timer {
        Log.d(tag, "Creating a restart timer to go off in ${(RESTART_PERIOD) / 60_000.0} minutes")
        return timer("ExperimentTimer", false,
                RESTART_PERIOD, RESTART_PERIOD / 3) {
            Log.d(tag, "Want to perform restart. Allowed: $restartAllowed")
            if (restartAllowed) {
                val intent = Intent(this@NetworkService, NetworkService::class.java)
                intent.putExtra(NetworkService.EXTRA_DEVICE_ID, getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(EXTRA_DEVICE_ID, null))
                intent.putExtra(NetworkService.EXTRA_NETWORK_ID, getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(EXTRA_NETWORK_ID, null))
                intent.putExtra(NetworkService.EXTRA_RESTART_ADHOC_FLAG, true)
                saveState(intent)
                val pendingIntent = PendingIntent.getService(applicationContext, 0, intent, 0)
                val alarmManager = getSystemService(AppCompatActivity.ALARM_SERVICE) as AlarmManager
                alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RESTART_OFFSET, pendingIntent)
                Log.w(tag, "Restart of service set in ${RESTART_OFFSET / 1_000} seconds")

                stopAdHocGracefully()
                stopSelf()
                System.exit(0)
            } else {
                systemWakeLock?.release()
                systemWakeLock?.acquire(RESTART_PERIOD)
            }
        }
    }

    private fun saveState(intent: Intent) {
        intent.putExtra(SAVED_REGISTRATIONS, classForCallbackRegistrations)
        if (startAdHocCallerClass != null)
            intent.putExtra(SAVED_STARTER, startAdHocCallerClass)
    }

    private suspend fun reloadSavedState(intent: Intent) {
        Log.d(tag, "Reloading saved state.")
        classForCallbackRegistrations = intent.getStringExtra(SAVED_REGISTRATIONS)
        Log.d(tag, "registered Services from $classForCallbackRegistrations")
        try {
            val clazz = Class.forName(classForCallbackRegistrations)
            val instance = clazz.getMethod("getInstance").invoke(null)
            Log.d(tag, "calling 'connect' on $instance")
            clazz.getMethod("connect").invoke(instance)
        } catch (e: Exception) {
            Log.e(tag, "Couldn't re-register $classForCallbackRegistrations with static class access", e)
        }
        delay(2000)

        val startCaller = intent.getStringExtra(SAVED_STARTER)
        Log.d(tag, "starting ad hoc from $startCaller")
        if (startCaller != null) {
            try {
                val clazz = Class.forName(startCaller)
                val instance = clazz.getMethod("getInstance").invoke(null)
                clazz.getMethod("startRobustCommunication").invoke(instance)
            } catch (e: Exception) {
                Log.e(tag, "Couldn't restart ad hoc class $startCaller", e)
            }
        }
    }

    private fun isConnected(ssid: String): Boolean {
        val wifiInfo = wifiManager.connectionInfo
        val currentSsid = wifiInfo.ssid
        return currentSsid == "\"$ssid\""
    }

    fun getKodein() = kodein
}

fun algorithmMessageToBrocoliMessage(fromMessage: AlgorithmContentMessage): BrocoliMessage =
        BrocoliMessage(
                from = UserID(fromMessage.fromId),
                to = UserID(fromMessage.toId),
                serviceId = fromMessage.serviceId,
                timestamp = fromMessage.timestamp,
                ttlHours = fromMessage.ttlHours,
                priority = fromMessage.priority,
                messageBody = fromMessage.content.clone()

        )

fun brocoliMessageToAlgorithmMessage(fromMessage: BrocoliMessage): AlgorithmContentMessage =
        AlgorithmContentMessage(
                fromMessage.id,
                fromMessage.from.id,
                fromMessage.to.id,
                fromMessage.serviceId,
                fromMessage.timestamp,
                fromMessage.ttlHours,
                fromMessage.priority,
                fromMessage.messageBody.clone()
        )

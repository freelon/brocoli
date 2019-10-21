package de.upb.cs.brocoli.connectivity

import android.content.Context
import android.util.Log
import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.instance
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import de.upb.cs.brocoli.connectivity.ConnectionResolution.Status
import de.upb.cs.brocoli.library.UserID
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.timer

/**
 * [renewDiscoveryDelay] number of milliseconds before the discovery is stopped and restarted
 */
class NearbyConnectivity(kodein: Kodein, val ownUserId: UserID, private val adHocServiceName: String, private val renewDiscoveryDelay: Long, val checkForLostNeighborsDelay: Long) : Connectivity {
    companion object {
        private val TAG = NearbyConnectivity::class.java.simpleName
        const val BYTES_SENT_COUNT = "bytes_sent_count"
        const val BYTES_RECEIVED_COUNT = "bytes_received_count"
        const val SHARED_PREFS_NAME = "NearbyConnectivity"
        const val IDLE_BEFORE_DISCONNECT_TIME = 15000L
    }

    /**
     * The [UserID] of the device the interface is instantiated on.
     */
    override val ownId: UserID
        get() = ownUserId

    private val p2pStrategy: Strategy = kodein.instance()

    private val payloadCounter = AtomicLong()

    private val applicationContext: Context = kodein.instance()
    private val sharedPreferences = applicationContext.getSharedPreferences(TAG, Context.MODE_PRIVATE)

    private val connectionsClient = Nearby.getConnectionsClient(applicationContext)
    private var isAdvertising = false
    private var isDiscovering = false

    /**
     * Maps from the endpointName (the user friendly name, supposed to be our UserID) to the Neighbor instance representing that name.
     */
    private val neighborMap: MutableMap<String, NearbyNeighbor> = ConcurrentHashMap()
    /**
     * Maps from the endpointId to the endpointName (i.e. the "user friendly" name)
     */
    private val deviceNames: MutableMap<String, String> = ConcurrentHashMap()

    private val openConnections: MutableSet<Neighbor> = mutableSetOf()

    private val lastInteractionsMap: MutableMap<String, Long> = ConcurrentHashMap()

    private val refoundNeighbors: MutableSet<Neighbor> = mutableSetOf()

    /* all interfaces that we get from whoever is using this wrapper */
    private lateinit var outerDiscoveryCallback: EndpointDiscoveryCallback
    private lateinit var outerConnectionLifecycleCallback: ConnectionLifecycleCallback
    private lateinit var outerPayloadCallback: PayloadCallback

    private var discoveryTimer: Timer? = null
    private var checkForLostNeighborsTimer: Timer? = null
    private var checkForInactiveConnectionsTimer: Timer? = null

    private val sendListenerMap: MutableMap<Long, Pair<NearbyNeighbor, (Boolean, String?) -> Unit>> = ConcurrentHashMap()

    private fun validateEndPointName(endpointId: String): Boolean {
        var result = true
        try {
            //Validate if End Point is in the form of User ID.
            UserID(endpointId)
        } catch (e: IllegalArgumentException) {
            result = false
        }
        return result
    }

    private val nearbyConnectionLifecycleCallback = object : com.google.android.gms.nearby.connection.ConnectionLifecycleCallback() {
        override fun onConnectionResult(endpointId: String, connectionResolution: com.google.android.gms.nearby.connection.ConnectionResolution) {
            Log.d(TAG, "onConnectionResult(${ownId.id}): $endpointId, $connectionResolution")
            Log.d(TAG, "${deviceNames[endpointId]}, ${neighborMap[deviceNames[endpointId] ?: ""]}")
            val neighbor = neighborMap[deviceNames[endpointId] ?: return] ?: return

            val status = connectionResolution.status

            if (status.isSuccess)
                openConnections.add(neighbor)

            val ourStatus = when (status.statusCode) {
                CommonStatusCodes.SUCCESS -> Status.ConnectionStatusCode.OK
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> Status.ConnectionStatusCode.CONNECTION_REJECTED
                else -> Status.ConnectionStatusCode.ERROR
            }
            outerConnectionLifecycleCallback.onConnectionResult(neighbor, ConnectionResolution(Status(ourStatus, connectionResolution.status.statusMessage)))
        }

        override fun onDisconnected(endpointId: String) {
            val neighbor = neighborMap[deviceNames[endpointId] ?: return] ?: return

            openConnections.remove(neighbor)

            outerConnectionLifecycleCallback.onDisconnected(neighbor)
        }

        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            if (!validateEndPointName(connectionInfo.endpointName)) {
                Log.w(TAG, "onConnectionInitiated failure due to special characters in '${connectionInfo.endpointName}'. Discarded connection request.")
                connectionsClient.rejectConnection(endpointId)
                return
            }
            Log.d(TAG, "onConnectionInitiated from $endpointId - ${connectionInfo.endpointName}")
            val neighbor = getOrCreateNeighbor(connectionInfo.endpointName, NearbyNeighbor(connectionInfo.endpointName, endpointId))
            outerConnectionLifecycleCallback.onConnectionInitiated(neighbor, ConnectionInfo(neighbor.id, connectionInfo.isIncomingConnection))

        }
    }

    /**
     * Starts advertising an endpoint for a local app using [ownId] as the advertised device name.
     * @param connectionLifecycleCallback is the callback that will be invoked for every incoming connection
     * @param advertisingListener used to get the results of the advertising (advertise succeeded or failed)
     */
    override fun startAdvertising(connectionLifecycleCallback: ConnectionLifecycleCallback,
                                  advertisingListener: (success: Boolean, optionalMessage: String?) -> Unit) {
        if (isAdvertising) {
            advertisingListener(false, "$this is already advertising.")
            return
        }
        isAdvertising = true
        outerConnectionLifecycleCallback = connectionLifecycleCallback

        val task = connectionsClient.startAdvertising(ownUserId.id, adHocServiceName, nearbyConnectionLifecycleCallback,
                AdvertisingOptions.Builder().setStrategy(p2pStrategy).build())

        task.addOnCompleteListener {
            advertisingListener(it.isSuccessful, null)
        }
    }

    /**
     * Stops advertising.
     */
    override fun stopAdvertising() {
        isAdvertising = false
        connectionsClient.stopAdvertising()
    }

    /**
     * Starts discovery for remote endpoints with the specified service ID.
     * @param discoveryCallback is the callback that will be invoked for every neighbor that is discovered or lost.
     * @param discoveringListener used to get the results of the discovering (starting discovery succeeded or failed)
     */
    override fun startDiscovery(discoveryCallback: EndpointDiscoveryCallback, discoveringListener: (success: Boolean, optionalMessage: String?) -> Unit) {
        if (isDiscovering) {
            Log.d(TAG, "Already discovering, starting lost neighbor check...")
            checkForLostNeighborsTimer?.cancel()
            checkForLostNeighborsTimer = null
            checkForLostNeighborsTimer = timer("NearbyConnectivity.CheckForLostNeighborsTimer", false, checkForLostNeighborsDelay, checkForLostNeighborsDelay) {
                checkForLostNeighborsTimer?.cancel()
                checkForLostNeighborsTimer = null
                if (isDiscovering) {
                    Log.d(TAG, "Checking for lost neighbors...")
                    val lostNeighbors = neighborMap.values.filterNot { refoundNeighbors.contains(it) }

                    // first clean up the neighbors list before letting the callback know about the changes
                    lostNeighbors.forEach {
                        neighborMap.remove(it.id)
                    }
                    lostNeighbors.forEach {
                        discoveryCallback.onEndpointLost(it)
                        Log.d(TAG, "Rescanning removed neighbor $it")
                    }
                }
            }
        }

        isDiscovering = true
        outerDiscoveryCallback = discoveryCallback

        val endpointDiscoveryCallback = object : com.google.android.gms.nearby.connection.EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, discoveredEndpointInfo: DiscoveredEndpointInfo) {
                if (!validateEndPointName(discoveredEndpointInfo.endpointName)) {
                    Log.w(TAG, "onEndpointFound failure due to special characters in '${discoveredEndpointInfo.endpointName}'. Ignoring endpoint.")
                    return
                }
                deviceNames[endpointId] = discoveredEndpointInfo.endpointName
                val neighbor = getOrCreateNeighbor(discoveredEndpointInfo.endpointName, NearbyNeighbor(discoveredEndpointInfo.endpointName, endpointId))
                outerDiscoveryCallback.onEndpointFound(neighbor,
                        de.upb.cs.brocoli.connectivity.DiscoveredEndpointInfo(
                                discoveredEndpointInfo.serviceId,
                                discoveredEndpointInfo.endpointName))
            }

            override fun onEndpointLost(endpointId: String) {
                val endpointName = deviceNames[endpointId] ?: return
                val neighbor = neighborMap.remove(endpointName)
                if (neighbor != null)
                    outerDiscoveryCallback.onEndpointLost(neighbor)
            }
        }

        refoundNeighbors.clear()

        connectionsClient.startDiscovery(adHocServiceName,
                endpointDiscoveryCallback,
                DiscoveryOptions.Builder().setStrategy(p2pStrategy).build())
                .addOnCompleteListener {
                    discoveringListener(true, null)
                }.addOnFailureListener {
                    discoveringListener(false, it.localizedMessage)
                }

        discoveryTimer?.cancel()
        discoveryTimer = createDiscoveryTimer()
    }

    private fun createDiscoveryTimer(): Timer =
            timer("NearbyConnectivity.DiscoveryTimer", false, renewDiscoveryDelay, renewDiscoveryDelay) {
                Log.d(TAG, "re-discovery timer running: isDiscovering($isDiscovering)")
                if (isDiscovering) {
                    connectionsClient.stopDiscovery()
                    startDiscovery(outerDiscoveryCallback, { _, _ -> })
                }
            }

    private fun getOrCreateNeighbor(key: String, new: NearbyNeighbor): NearbyNeighbor {
        val result = neighborMap[key]
        val neighbor = if (result == null || result.nearbyId != new.nearbyId) {
            neighborMap[key] = new
            new
        } else {
            result
        }
        refoundNeighbors.add(neighbor)
        return neighbor
    }

    /**
     * Stops discovering.
     */
    override fun stopDiscovery() {
        isDiscovering = false
        connectionsClient.stopDiscovery()
        discoveryTimer?.cancel()
    }

    /**
     * Accepts a connection to an endpoint.
     * @param endpoint the neighbor with whom we accept to connect
     * @param callback is the callback that will be invoked for every payload that is sent/received from that neighbor
     * @param acceptListener used to get the results of accepting connection
     */
    override fun acceptConnection(endpoint: Neighbor, callback: PayloadCallback, acceptListener: (success: Boolean, optionalMessage: String?) -> Unit) {
        check(endpoint is NearbyNeighbor)
        outerPayloadCallback = callback

        val payloadCallback = object : com.google.android.gms.nearby.connection.PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: com.google.android.gms.nearby.connection.Payload) {

                val neighbor = neighborMap[deviceNames[endpointId] ?: return] ?: return
                if (payload.type != com.google.android.gms.nearby.connection.Payload.Type.BYTES) {

                }
                when (payload.type) {
                    com.google.android.gms.nearby.connection.Payload.Type.BYTES -> {
                        val content = payload.asBytes() ?: return
                        Log.d(TAG, "received payload from $neighbor (size: ${content.size} bytes)")
                        outerPayloadCallback.onPayloadReceived(neighbor, createPayload(content))
                        countReceivedBytes(content.size)
                        triggerConnection(endpointId)
                    }
                    com.google.android.gms.nearby.connection.Payload.Type.STREAM -> {
                        val contentStream = payload.asStream() ?: return
                        val content = contentStream.asInputStream().readBytes()
                        Log.d(TAG, "received payload from $neighbor (size: ${content.size} bytes)")
                        outerPayloadCallback.onPayloadReceived(neighbor, createPayload(content))
                        countReceivedBytes(content.size)
                        triggerConnection(endpointId)
                    }
                    else -> {
                        Log.d(TAG, "dismissed payload $payload from $endpointId($neighbor) because it has a wrong type")
                    }
                }
            }

            override fun onPayloadTransferUpdate(endpointId: String, payloadTransferUpdate: PayloadTransferUpdate) {
                // Log.d(TAG, "payload transfer update discarded")
                if (payloadTransferUpdate.status == PayloadTransferUpdate.Status.SUCCESS) {
                    val sendListener = sendListenerMap[payloadTransferUpdate.payloadId]
                    if (sendListener != null)
                        sendListener.second(true, "")
                } else if (payloadTransferUpdate.status == PayloadTransferUpdate.Status.CANCELED || payloadTransferUpdate.status == PayloadTransferUpdate.Status.FAILURE) {
                    val sendListener = sendListenerMap[payloadTransferUpdate.payloadId]
                    if (sendListener != null)
                        sendListener.second(false, "failed because: ${
                        when (payloadTransferUpdate.status) {
                            PayloadTransferUpdate.Status.CANCELED -> "Cancelled"
                            PayloadTransferUpdate.Status.FAILURE -> "Failure"
                            else -> ""
                        }
                        } (google status code)")
                }
                triggerConnection(endpointId)
            }
        }

        connectionsClient.acceptConnection((endpoint as NearbyNeighbor).nearbyId, payloadCallback).addOnCompleteListener {
            acceptListener(it.isSuccessful, it.exception?.localizedMessage)
        }
    }

    /**
     * Cancels a Payload currently in-flight to or from remote endpoint(s)
     * @param payloadId the id of the payload we want to cancel sending
     * @param cancelListener used to get the result of canceling sending the payload
     */
    override fun cancelPayload(payloadId: Long, cancelListener: (success: Boolean, optionalMessage: String?) -> Unit) {
        connectionsClient.cancelPayload(payloadId).addOnCompleteListener {
            cancelListener(it.isSuccessful, it.exception?.localizedMessage)
        }
    }

    /**
     * Disconnects from a remote endpoint.
     * @param endpoint the neighbor from whom we want to disconnect
     */
    override fun disconnectFromEndpoint(endpoint: Neighbor) {
        require(endpoint is NearbyNeighbor, { "wrong neighbor type (has to be emitted by this implementation, i.e. NearbyNeighbor" })
        openConnections.remove(endpoint)
        connectionsClient.disconnectFromEndpoint((endpoint as NearbyNeighbor).nearbyId)
        outerConnectionLifecycleCallback.onDisconnected(endpoint)
    }

    /**
     * Rejects a connection to a remote endpoint.
     * @param endpoint the neighbor with whom we refuse to connect
     * @param rejectListener used to get the result of rejecting the connection
     */
    override fun rejectConnection(endpoint: Neighbor, rejectListener: (success: Boolean) -> Unit) {
        require(endpoint is NearbyNeighbor, { "wrong neighbor type (has to be emitted by this implementation, i.e. NearbyNeighbor" })
        connectionsClient.disconnectFromEndpoint((endpoint as NearbyNeighbor).nearbyId)
        rejectListener(true)
    }

    /**
     * Sends a request to connect to a remote endpoint
     * @param endpoint  Neighbor with whom we want to connect
     * @param callback is the callback that notifies about the success of the connection attempt
     * @param requestListener used to get the result of requesting Connection
     */
    override fun requestConnection(endpoint: Neighbor, callback: ConnectionLifecycleCallback, requestListener: (success: Boolean, optionalMessage: String?) -> Unit) {
        require(endpoint is NearbyNeighbor, { "wrong neighbor type (has to be emitted by this implementation, i.e. NearbyNeighbor" })
        outerConnectionLifecycleCallback = callback
        connectionsClient.requestConnection(ownUserId.id, (endpoint as NearbyNeighbor).nearbyId, nearbyConnectionLifecycleCallback)
                .addOnCompleteListener {
                    requestListener(it.isSuccessful, if (!it.isSuccessful) "error: ${it.exception}" else null)
                }
    }

    /**
     * Sends a Payload to a remote endpoint.
     * @param endpoint Neighbor to whom to want to send payload
     * @param payload the payload we want to sent
     * @param sendListener used to get the result of sending payload
     */
    override fun sendPayload(endpoint: Neighbor, payload: Payload, sendListener: (success: Boolean, optionalMessage: String?) -> Unit) {
        require(endpoint is NearbyNeighbor, { "wrong neighbor type (has to be emitted by this implementation, i.e. NearbyNeighbor" })
        val payloadBytes = payload.getBytes()
        Log.d(TAG, "Payload ${payload.getId()} info: ${payloadBytes.size} bytes, ${ConnectionsClient.MAX_BYTES_DATA_SIZE} allowed to send directly as array")
        val nearbyPayload = if (payloadBytes.size > ConnectionsClient.MAX_BYTES_DATA_SIZE) {
            Log.d(TAG, "Sending as stream")
            com.google.android.gms.nearby.connection.Payload.fromStream(payloadBytes.inputStream())
        } else {
            com.google.android.gms.nearby.connection.Payload.fromBytes(payloadBytes)
        }
        sendListenerMap[nearbyPayload.id] = Pair(endpoint as NearbyNeighbor, sendListener)
        connectionsClient.sendPayload(endpoint.nearbyId, nearbyPayload)
        countSentBytes(payloadBytes.size)
        triggerConnection(endpoint.nearbyId)
    }

    /**
     * Variant of sendPayload(String, Payload) that takes a list of remote endpoint IDs.
     * @param endpoints List of neighbors
     * @param payload we want to sent
     * @param sendAllListener used to get result of sending payload to list of neighbors
     */
    override fun sendPayload(endpoints: List<Neighbor>, payload: Payload, sendAllListener: (success: Boolean, optionalMessage: String?) -> Unit) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    /**
     * Disconnects from, and removes all traces of all connected and/or discovered endpoints
     */
    override fun stopAllEndpoints() {
        openConnections.forEach {
            outerConnectionLifecycleCallback.onDisconnected(it)
        }
        openConnections.clear()
        connectionsClient.stopAllEndpoints()
    }

    /**
     * Gets a list of currently visible neighbors. This list will not be updated.
     */
    override fun getNeighbors(): List<Neighbor> = neighborMap.values.toList()

    /**
     * Retrieves a list of neighbors to which this device is currently connected. The list will not be updated.
     */
    override fun getConnectedNeighbors(): List<Neighbor> = openConnections.toList()

    /**
     * Creates a new unique payload object.
     */
    override fun createPayload(from: ByteArray): Payload = NearbyPayload(payloadCounter.incrementAndGet(), from)

    /**
     * Stops all activity in the connectivity component, which should not be used after this anymore (undefined behavior)
     */
    override fun close() {
        checkForLostNeighborsTimer?.cancel()
        checkForLostNeighborsTimer = null
        checkForInactiveConnectionsTimer?.cancel()
    }

    override fun initiate() {
        checkForInactiveConnectionsTimer?.cancel()
        checkForInactiveConnectionsTimer = timer("CheckForInactiveConnectionsTimer", true, IDLE_BEFORE_DISCONNECT_TIME / 3, IDLE_BEFORE_DISCONNECT_TIME / 3) {
            checkForTimedOutConnections()
        }
    }

    private fun countSentBytes(byteCount: Int) {
        val old = sharedPreferences.getInt(BYTES_SENT_COUNT, 0)
        sharedPreferences.edit().putInt(BYTES_SENT_COUNT, old + byteCount).apply()
    }

    private fun countReceivedBytes(byteCount: Int) {
        val old = sharedPreferences.getInt(BYTES_RECEIVED_COUNT, 0)
        sharedPreferences.edit().putInt(BYTES_RECEIVED_COUNT, old + byteCount).apply()
    }

    private fun checkForTimedOutConnections() {
        val currentTime = System.currentTimeMillis()
        Log.d(TAG, "Checking for timed out connections:")
        lastInteractionsMap.forEach { (id, timestamp) ->
            Log.d(TAG, "Device: $id, age: ${(currentTime - timestamp) / 1000}s")
        }
        val timedOut = lastInteractionsMap.filter { it.value + IDLE_BEFORE_DISCONNECT_TIME < currentTime }.toList()
        timedOut.forEach {
            val ourId = deviceNames[it.first]
            val neighbor = if (ourId != null) neighborMap[ourId] else null
            Log.d(TAG, "disconnecting from $neighbor (${it.first}) due to inactivity")
            if (neighbor != null) {
                disconnectFromEndpoint(neighbor)
            }
            lastInteractionsMap.remove(it.first)
        }
    }

    private fun triggerConnection(nearbyId: String) {
        lastInteractionsMap[nearbyId] = System.currentTimeMillis()
    }
}

data class NearbyNeighbor(override val id: String, val nearbyId: String) : Neighbor(id)

data class NearbyPayload(private val payloadId: Long, private val contentBytes: ByteArray) : Payload {
    /**
     * Returns the unique ID for this payload.
     */
    override fun getId(): Long = payloadId

    /**
     * Returns the complete payload content.
     */
    override fun getBytes(): ByteArray = contentBytes
}

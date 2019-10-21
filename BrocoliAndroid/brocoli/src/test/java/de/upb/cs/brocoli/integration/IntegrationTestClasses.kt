package de.upb.cs.brocoli.integration

import de.upb.cs.brocoli.connectivity.*
import de.upb.cs.brocoli.library.UserID
import de.upb.cs.brocoli.neighborhoodwatch.LogEvent
import de.upb.cs.brocoli.neighborhoodwatch.NeighborhoodWatcherLog
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicInteger


class FakeConnectivity(val deviceID: UserID, private val neighborhoodManager: NeighborhoodManager, val serviceId: String) : Connectivity {
    private val logger = KotlinLogging.logger(FakeConnectivity::class.java.simpleName)
    private val availableDevices = mutableSetOf<Neighbor>()
    @Volatile
    private lateinit var connectionCallback: ConnectionLifecycleCallback
    @Volatile
    private var initCount = 0
    @Volatile
    private var payloadCallback: PayloadCallback? = null
    @Volatile
    private var discoveryCallback: EndpointDiscoveryCallback? = null
    private val connections: ConcurrentMap<Neighbor, Connection> = ConcurrentHashMap()

    enum class ConnectionState {
        RequestedIncoming, RequestedOutgoing, AcceptedIncoming, AcceptedOutgoing, Connected, Failure, OtherAccepted
    }

    data class Connection(val neighbor: Neighbor, val outgoing: Boolean, val connectionState: ConnectionState)

    companion object { // global, so that there is no duplication of IDs without using random ones
        private var payloadCount = AtomicInteger(0)
    }

    /**
     * The [UserID] of the device the interface is instantiated on.
     */
    override val ownId: UserID = deviceID

    init {
        neighborhoodManager.registerConnectivity(this)
    }

    /**
     * Starts advertising an endpoint for a local app using the [serviceId] and [ownId].
     * @param connectionLifecycleCallback is the callback that will be invoked for every incoming connection
     * @param advertisingListener used to get the results of the advertising (advertise succeeded or failed)
     */
    @Synchronized
    override fun startAdvertising(connectionLifecycleCallback: ConnectionLifecycleCallback, advertisingListener: (success: Boolean, optionalMessage: String?) -> Unit) {
        connectionCallback = connectionLifecycleCallback
        initCount++
        neighborhoodManager.addAdvertiser(this, serviceId)
        advertisingListener(true, null)
    }

    /**
     * Stops advertising.
     */
    @Synchronized
    override fun stopAdvertising() {
        neighborhoodManager.removeAdvertiser(this)
    }

    /**
     * Starts discovery for remote endpoints with the specified service ID.
     * @param discoveryCallback is the callback that will be invoked for every neighbor that is discovered or lost.
     * @param discoveringListener used to get the results of the discovering (starting discovery succeeded or failed)
     */
    @Synchronized
    override fun startDiscovery(discoveryCallback: EndpointDiscoveryCallback, discoveringListener: (success: Boolean, optionalMessage: String?) -> Unit) {
        this.discoveryCallback = discoveryCallback
        neighborhoodManager.addDiscoverer(this, serviceId)
        launch {
            delay(NETWORK_OPERATION_DELAY)
            discoveringListener(true, null)
        }
    }

    /**
     * Accepts a connection to an endpoint.
     * @param endpoint the neighbor with whom we accept to connect
     * @param callback is the callback that will be invoked for every payload that is sent/received from that neighbor
     * @param acceptListener used to get the results of accepting connection
     */
    @Synchronized
    override fun acceptConnection(endpoint: Neighbor, callback: PayloadCallback, acceptListener: (success: Boolean, optionalMessage: String?) -> Unit) {
        payloadCallback = callback
        val connection = connections[endpoint]
        logger.debug { "$deviceID: connection accept for $connection" }
        if (connection != null) {
            if (connection.outgoing && connection.connectionState == ConnectionState.OtherAccepted) {
                neighborhoodManager.sendConnectionRequest(this, UserID(endpoint.id), NeighborhoodManager.RequestType.ConnectionAccept)
                connections[endpoint] = Connection(endpoint, connection.outgoing, ConnectionState.Connected)
                connectionCallback.onConnectionResult(endpoint, ConnectionResolution(ConnectionResolution.Status(ConnectionResolution.Status.ConnectionStatusCode.OK)))
            } else if (!connection.outgoing && connection.connectionState == ConnectionState.RequestedIncoming) {
                neighborhoodManager.sendConnectionRequest(this, UserID(endpoint.id), NeighborhoodManager.RequestType.ConnectionAccept)
                connections[endpoint] = Connection(endpoint, connection.outgoing, ConnectionState.AcceptedIncoming)
            } else {
                acceptListener(false, "failed because $connection is not a valid state for sending an accept")
            }
            acceptListener(true, null)
        } else {
            acceptListener(false, "No request to accept here")
        }
    }

    /**
     * Cancels a Payload currently in-flight to or from remote endpoint(s)
     * @param payloadId the id of the payload we want to cancel sending
     * @param cancelListener used to get the result of canceling sending the payload
     */
    @Synchronized
    override fun cancelPayload(payloadId: Long, cancelListener: (success: Boolean, optionalMessage: String?) -> Unit) {
    }

    /**
     * Disconnects from a remote endpoint.
     * @param endpoint the neighbor from whom we want to disconnect
     */
    @Synchronized
    override fun disconnectFromEndpoint(endpoint: Neighbor) {
        neighborhoodManager.disconnect(this, UserID(endpoint.id))
    }

    /**
     * Rejects a connection to a remote endpoint.
     * @param endpoint the neighbor with whom we refuse to connect
     * @param rejectListener used to get the result of rejecting the connection
     */
    @Synchronized
    override fun rejectConnection(endpoint: Neighbor, rejectListener: (success: Boolean) -> Unit) {
        neighborhoodManager.sendConnectionRequest(this, UserID(endpoint.id), NeighborhoodManager.RequestType.ConnectionReject)
        rejectListener(true)
    }

    /**
     * Sends a request to connect to a remote endpoint
     * @param endpoint  Neighbor with whom we want to connect
     * @param callback is the callback that notifies about the success of the connection attempt
     * @param requestListener used to get the result of requesting Connection
     */
    @Synchronized
    override fun requestConnection(endpoint: Neighbor, callback: ConnectionLifecycleCallback, requestListener: (success: Boolean, optionalMessage: String?) -> Unit) {
        if (connections[endpoint] != null) {
            requestListener(false, "there is already a connection for neighbor $endpoint")
            return
        }

        connectionCallback = callback
        initCount++
        logger.debug { "${deviceID.id} set connectionCallback to $callback" }
        connections[endpoint] = Connection(endpoint, true, ConnectionState.RequestedOutgoing)
        neighborhoodManager.sendConnectionRequest(this, UserID(endpoint.id), NeighborhoodManager.RequestType.ConnectionRequest)
        requestListener(true, null)
    }

    /**
     * Sends a Payload to a remote endpoint.
     * @param endpoint Neighbor to whom to want to send payload
     * @param payload the payload we want to sent
     * @param sendListener used to get the result of sending payload
     */
    //@Synchronized
    override fun sendPayload(endpoint: Neighbor, payload: Payload, sendListener: (success: Boolean, optionalMessage: String?) -> Unit) {
        neighborhoodManager.sendPayload(this, UserID(endpoint.id), payload) { result ->
            logger.debug { "FakeConnectivity (${ownId.id}): performing callback with result: $result" }
            sendListener(result, null)
        }
    }

    /**
     * Variant of sendPayload(String, Payload) that takes a list of remote endpoint IDs.
     * @param endpoints List of neighbors
     * @param payload we want to sent
     * @param sendAllListener used to get result of sending payload to list of neighbors
     */
    @Synchronized
    override fun sendPayload(endpoints: List<Neighbor>, payload: Payload, sendAllListener: (success: Boolean, optionalMessage: String?) -> Unit) {
        TODO("not implemented")
    }

    /**
     * Disconnects from, and removes all traces of all connected and/or discovered endpoints
     */
    @Synchronized
    override fun stopAllEndpoints() {
        neighborhoodManager.devices.forEach { deviceID, _ ->
            neighborhoodManager.disconnect(this, deviceID)
        }
    }

    /**
     * Gets a list of currently visible neighbors. This list will not be updated.
     */
    @Synchronized
    override fun getNeighbors(): List<Neighbor> = availableDevices.toList()

    /**
     * Retrieves a list of neighbors to which this device is currently connected. The list will not be updated.
     */
    @Synchronized
    override fun getConnectedNeighbors(): List<Neighbor> = listOf()

    /**
     * Stops discovering.
     */
    @Synchronized
    override fun stopDiscovery() {
        neighborhoodManager.removeDiscoverer(this)
    }

    /**
     * Stops all activity in the connectivity component, which should not be used after this anymore (undefined behavior)
     */
    override fun close() {
        // nothing to do here
    }

    /**
     * Creates a new unique payload object.
     */
    //@Synchronized
    override fun createPayload(from: ByteArray): Payload = object : Payload {
        private val id = payloadCount.incrementAndGet().toLong()
        override fun getId(): Long = id

        override fun getBytes(): ByteArray = from

        override fun toString(): String = "IntegrationPayload(id: ${this.getId()}, payload: ${this.getBytes()})"
    }

    /**
     * CB for NM
     */
    @Synchronized
    fun pushPayload(payload: Payload, from: UserID) {
        logger.debug { "${deviceID.id} received payload '$payload' push from '$from'" }
        payloadCallback!!.onPayloadReceived(object : Neighbor(neighborhoodManager.devices[from]!!.deviceID.id) {}, payload)
    }

    @Synchronized
    fun pushConnectionRequest(requestType: NeighborhoodManager.RequestType, from: UserID) {
        val neighbor = FakeNeighbor(from.id)
        val connection = connections[neighbor]
        logger.debug { "${deviceID.id} received connection request '$requestType' push from '$from' (connectionState: $connection" }
        when (requestType) {
            NeighborhoodManager.RequestType.ConnectionRequest -> {
                // println("FakeConnectivity($this, ${deviceID.id}) has connectionCallback initialization count = $initCount")
                // println("FakeConnectivity($this, ${deviceID.id}) has connectionCallback=$connectionCallback")
                if (connection == null) {
                    connections[neighbor] = Connection(neighbor, false, ConnectionState.RequestedIncoming)
                    logger.debug { "${deviceID.id} state now ${connections[neighbor]}" }
                    connectionCallback.onConnectionInitiated(neighbor, ConnectionInfo(from.id, true))
                } else {
                    logger.debug { "${deviceID.id} dismissed connection request, because a connection is already pending" }
                }
            }
            NeighborhoodManager.RequestType.ConnectionReject -> {
                if (connection != null) {
                    connections[neighbor] = Connection(neighbor, connection.outgoing, ConnectionState.Failure)
                    connectionCallback.onConnectionResult(neighbor, ConnectionResolution(ConnectionResolution.Status(ConnectionResolution.Status.ConnectionStatusCode.CONNECTION_REJECTED)))
                } else {
                    logger.debug { "${deviceID.id} dismissed rejection request, because no connection request is pending" }
                }
            }
            NeighborhoodManager.RequestType.ConnectionAccept -> {
                if (connection != null) {
                    if (connection.outgoing) {
                        connections[neighbor] = Connection(neighbor, connection.outgoing, ConnectionState.OtherAccepted)
                        connectionCallback.onConnectionInitiated(neighbor, ConnectionInfo(from.id, true))
                    } else {
                        // the other party started the connection, first we accepted, now they did
                        connections[neighbor] = Connection(neighbor, connection.outgoing, ConnectionState.Connected)
                        connectionCallback.onConnectionResult(neighbor, ConnectionResolution(ConnectionResolution.Status(ConnectionResolution.Status.ConnectionStatusCode.OK)))
                    }
                } else {
                    logger.debug { "${deviceID.id} dismissed connection accept, because no connection is pending" }
                }
            }
        }
    }

    @Synchronized
    fun pushDiscovery(otherId: UserID, serviceId: String) {
        logger.debug { "FakeConnectivity($this, ${deviceID.id}) got notified about finding $otherId on service $serviceId" }
        if (availableDevices.none { it.id == otherId.id }) {
            val neighbor = FakeNeighbor(otherId.id)
            availableDevices.add(neighbor)
            discoveryCallback!!.onEndpointFound(neighbor, DiscoveredEndpointInfo(serviceId, otherId.id))
        }
    }

    override fun initiate() {
    }
}

class NeighborhoodManager {
    private val logger = KotlinLogging.logger(NeighborhoodManager::class.java.simpleName)
    val devices = mutableMapOf<UserID, FakeConnectivity>()
    val advertisers = mutableMapOf<FakeConnectivity, String>() // connectivity + advertised service id
    val discoveringDevices = mutableMapOf<FakeConnectivity, String>() // connectivity + searched for service id
    val activeConnections = mutableSetOf<Set<FakeConnectivity>>()

    @Synchronized
    fun registerConnectivity(connectivity: FakeConnectivity) {
        devices[connectivity.deviceID] = connectivity
    }

    @Synchronized
    fun addAdvertiser(fakeConnectivity: FakeConnectivity, serviceId: String) {
        advertisers[fakeConnectivity] = serviceId
        launch {
            val discoverersToNotify = discoveringDevices.filter { it.value == serviceId && it.key != fakeConnectivity }.keys.toList()
            delay(DISCOVER_DELAY)
            discoverersToNotify.forEach {
                it.pushDiscovery(fakeConnectivity.deviceID, serviceId)
            }
        }
    }

    @Synchronized
    fun removeAdvertiser(fakeConnectivity: FakeConnectivity) {
        advertisers.remove(fakeConnectivity)
    }

    @Synchronized
    fun addDiscoverer(fakeConnectivity: FakeConnectivity, serviceId: String) {
        discoveringDevices[fakeConnectivity] = serviceId
        launch {
            val advertisersToFind = advertisers.filter { it.value == serviceId && it.key != fakeConnectivity }.keys
            logger.debug { "advertisers for ${fakeConnectivity.deviceID}: $advertisersToFind" }
            delay(DISCOVER_DELAY)
            advertisersToFind.forEach {
                it.pushDiscovery(fakeConnectivity.deviceID, serviceId)
            }
        }
    }

    @Synchronized
    fun removeDiscoverer(fakeConnectivity: FakeConnectivity) {
        // discoveringDevices.remove(fakeConnectivity)
    }

    @Synchronized
    fun sendPayload(from: FakeConnectivity, to: UserID, payload: Payload, successCallback: (Boolean) -> Unit) {
        launch {
            delay(NETWORK_OPERATION_DELAY)
            if (devices.containsKey(to)) {
                logger.debug { "NeighborhoodManager (${from.deviceID.id}): delivering message and making success callback" }
                devices[to]!!.pushPayload(payload, from.deviceID)
                successCallback(true)
            } else {
                logger.debug { "NeighborhoodManager (${from.deviceID.id}: making failure callback" }
                successCallback(false)
            }
        }
    }

    @Synchronized
    fun sendConnectionRequest(from: FakeConnectivity, to: UserID, requestType: RequestType) {
        check(devices.contains(to), { "target device not nearby" })
        logger.debug { "received $requestType from ${from.deviceID} to $to" }
        launch {
            delay(NETWORK_OPERATION_DELAY)
            devices[to]!!.pushConnectionRequest(requestType, from.deviceID)
        }
    }

    @Synchronized
    fun disconnect(from: FakeConnectivity, to: UserID) {
        activeConnections.removeIf {
            it.contains(from) && it.contains(devices[to])
        }
    }

    enum class RequestType {
        ConnectionRequest,
        ConnectionReject,
        ConnectionAccept
    }
}

class ConsoleLogWriter(private val deviceID: UserID) : NeighborhoodWatcherLog {
    /**
     * Removes all log entries
     */
    override fun clearLog() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    private val logger = KotlinLogging.logger(ConsoleLogWriter::class.java.simpleName)
    override fun addLogEntry(entry: LogEvent) {
        logger.info { "${deviceID.id}: $entry" }
    }

}

data class FakeNeighbor(override val id: String) : Neighbor(id)
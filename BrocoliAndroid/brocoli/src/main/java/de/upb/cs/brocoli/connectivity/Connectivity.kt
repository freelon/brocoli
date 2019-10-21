package de.upb.cs.brocoli.connectivity

import de.upb.cs.brocoli.library.UserID

/**
 * This interface should wraps connectivity implementations like the google nearby API. It allows to
 * observe the (dis-)appearing of neighbors, connecting to neighbors, and exchanging data with neighbors.
 */
interface Connectivity {
    /**
     * The [UserID] of the device the interface is instantiated on.
     */
    val ownId: UserID

    /**
     * Starts advertising an endpoint for a local app using [ownId] as the device id.
     * @param connectionLifecycleCallback is the callback that will be invoked for every incoming connection
     * @param advertisingListener used to get the results of the advertising (advertise succeeded or failed)
     */
    fun startAdvertising(connectionLifecycleCallback: ConnectionLifecycleCallback, advertisingListener: (@ParameterName("success") Boolean, @ParameterName("optionalMessage") String?) -> Unit)

    /**
     * Stops advertising.
     */
    fun stopAdvertising()

    /**
     * Starts discovery for remote endpoints with the specified service ID.
     * @param discoveryCallback is the callback that will be invoked for every neighbor that is discovered or lost.
     * @param discoveringListener used to get the results of the discovering (starting discovery succeeded or failed)
     */
    fun startDiscovery(discoveryCallback: EndpointDiscoveryCallback, discoveringListener: (@ParameterName("success") Boolean, @ParameterName("optionalMessage") String?) -> Unit)

    /**
     * Accepts a connection to an endpoint.
     * @param endpoint the neighbor with whom we accept to connect
     * @param callback is the callback that will be invoked for every payload that is sent/received from that neighbor
     * @param acceptListener used to get the results of accepting connection
     */
    fun acceptConnection(endpoint: Neighbor, callback: PayloadCallback, acceptListener: (@ParameterName("success") Boolean, @ParameterName("optionalMessage") String?) -> Unit)

    /**
     * Cancels a Payload currently in-flight to or from remote endpoint(s)
     * @param payloadId the id of the payload we want to cancel sending
     * @param cancelListener used to get the result of canceling sending the payload
     */
    fun cancelPayload(payloadId: Long, cancelListener: (@ParameterName("success") Boolean, @ParameterName("optionalMessage") String?) -> Unit)

    /**
     * Disconnects from a remote endpoint.
     * @param endpoint the neighbor from whom we want to disconnect
     */
    fun disconnectFromEndpoint(endpoint: Neighbor)

    /**
     * Rejects a connection to a remote endpoint.
     * @param endpoint the neighbor with whom we refuse to connect
     * @param rejectListener used to get the result of rejecting the connection
     */
    fun rejectConnection(endpoint: Neighbor, rejectListener: (@ParameterName("success") Boolean) -> Unit)

    /**
     * Sends a request to connect to a remote endpoint
     * @param endpoint  Neighbor with whom we want to connect
     * @param callback is the callback that notifies about the success of the connection attempt
     * @param requestListener used to get the result of requesting Connection
     */
    fun requestConnection(endpoint: Neighbor, callback: ConnectionLifecycleCallback, requestListener: (@ParameterName("success") Boolean, @ParameterName("optionalMessage") String?) -> Unit)

    /**
     * Sends a Payload to a remote endpoint.
     * @param endpoint Neighbor to whom to want to send payload
     * @param payload the payload we want to sent
     * @param sendListener used to get the result of sending payload
     */
    fun sendPayload(endpoint: Neighbor, payload: Payload, sendListener: (@ParameterName("success") Boolean, @ParameterName("optionalMessage") String?) -> Unit)

    /**
     * Variant of sendPayload(String, Payload) that takes a list of remote endpoint IDs.
     * @param endpoints List of neighbors
     * @param payload we want to sent
     * @param sendAllListener used to get result of sending payload to list of neighbors
     */
    fun sendPayload(endpoints: List<Neighbor>, payload: Payload, sendAllListener: (@ParameterName("success") Boolean, @ParameterName("optionalMessage") String?) -> Unit)

    /**
     * Disconnects from, and removes all traces of all connected and/or discovered endpoints
     */
    fun stopAllEndpoints()

    /**
     * Gets a list of currently visible neighbors. This list will not be updated.
     */
    fun getNeighbors(): List<Neighbor>

    /**
     * Retrieves a list of neighbors to which this device is currently connected. The list will not be updated.
     */
    fun getConnectedNeighbors(): List<Neighbor>

    /**
     * Stops discovering.
     */
    fun stopDiscovery()

    /**
     * Creates a new unique payload object.
     */
    fun createPayload(from: ByteArray): Payload

    /**
     * Stops all activity in the connectivity component, which should not be used after this anymore (undefined behavior)
     */
    fun close()

    fun initiate()
}

/**
 * Shows relevant information about a discovered endpoint.
 * @param serviceId the service id
 * @param endpointName the name of the endpoint (not necessarily the brocoli device id of the neighbor)
 */
data class DiscoveredEndpointInfo(val serviceId: String, val endpointName: String)

/**
 * Interface used to indicate a neighbor discovery or of loosing sight of one.
 */
interface EndpointDiscoveryCallback {
    fun onEndpointFound(neighbor: Neighbor, discoveryInfo: DiscoveredEndpointInfo)

    fun onEndpointLost(neighbor: Neighbor)
}

/**
 * This class contains information about a neighbor neighbor
 */
abstract class Neighbor(open val id: String)

/**
 * A payload wraps content to be sent to or from another party. It is uniquely identified.
 */
interface Payload {
    /**
     * Returns the unique ID for this payload.
     */
    fun getId(): Long

    /**
     * Returns the complete payload content.
     */
    fun getBytes(): ByteArray
}

/**
 * A callback that will be used currently only to indicate success/failure of the delivery of a payload.
 * Updates of how much is yet transferred are not used yet.
 */
interface PayloadCallback {
    /**
     * Will be invoked by the implementation once the payload is transferred completely and successfully.
     */
    fun onPayloadReceived(neighbor: Neighbor, payload: Payload)

    /**
     * Will currently only be invoked once, when the payload is received.
     */
    fun onPayloadTransferUpdate(neighbor: Neighbor, transferUpdate: PayloadTransferUpdate)
}

/**
 * Indicates the current state of a payload transfer.
 */
data class PayloadTransferUpdate(val payloadId: Long, val statusCode: StatusCode, val totalBytes: Long, val transferredBytes: Long) {
    enum class StatusCode {
        Cancelled,
        Failure,
        InProgress,
        Success
    }
}

/**
 *
 */
data class ConnectionInfo(val endpointName: String, val isIncomingConnection: Boolean)

/**
 * A callback for the connection lifecycle. Used to indicate an initiated connection, a disconnection,
 * or a connection resolution (successful connection or non-successful with a status code).
 */
interface ConnectionLifecycleCallback {
    /**
     * Is called when a connection was initiated. The connection has to be accepted to be usable.
     */
    fun onConnectionInitiated(neighbor: Neighbor, info: ConnectionInfo)

    /**
     * Is called when the an initiated connection is either accepted, rejected, or timed out.
     */
    fun onConnectionResult(neighbor: Neighbor, result: ConnectionResolution)

    /**
     * Is called when the connection to that device is stopped.
     */
    fun onDisconnected(neighbor: Neighbor)
}


/**
 * Indicates the result of a connection attempt.
 */
data class ConnectionResolution(val status: Status) {

    /**
     * Indicates the Status of a [ConnectionResolution].
     */
    data class Status(private val statusCode: ConnectionStatusCode, val statusMessage: String?) {
        constructor (statusCode: ConnectionStatusCode) : this(statusCode, null)

        fun getStatusCode(): ConnectionStatusCode {
            return statusCode
        }

        val success = this.statusCode == ConnectionStatusCode.OK
        val cancelled = this.statusCode == ConnectionStatusCode.CONNECTION_CANCELLED
        val interrupted = this.statusCode == ConnectionStatusCode.CONNECTION_INTERRUPTED
        val rejected = this.statusCode == ConnectionStatusCode.CONNECTION_REJECTED

        enum class ConnectionStatusCode {
            OK,
            ERROR,
            CONNECTION_REJECTED,
            CONNECTION_CANCELLED,
            CONNECTION_INTERRUPTED
        }
    }
}
package de.upb.cs.brocoli.neighborhoodwatch

import android.util.Log
import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.instance
import de.upb.cs.brocoli.connectivity.*
import de.upb.cs.brocoli.library.UserID
import de.upb.cs.brocoli.model.AlgorithmContentMessage
import de.upb.cs.brocoli.model.Message
import de.upb.cs.brocoli.model.MessageChooser
import de.upb.cs.brocoli.model.Pipe
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.concurrent.timer

const val NEIGHBORHOOD_WATCHER_TIMER_INTERVAL = "NeighborhoodWatcherScanInterval"

class NeighborhoodWatcherImplementation(kodein: Kodein, userId: UserID) : NeighborhoodWatcher {
    companion object {
        private val TAG = NeighborhoodWatcherImplementation::class.java.simpleName
    }

    private val logWriter: NeighborhoodWatcherLog = kodein.instance()
    private val connectivity: Connectivity = kodein.instance()
    private val random: Random = kodein.instance()
    private val timerPeriod: Long = kodein.instance(NEIGHBORHOOD_WATCHER_TIMER_INTERVAL)
    private val timerName = "NeighborhoodWatcherImplementation.discoveryAndConnectTimer"
    private val ourDeviceId = userId.id
    private var discoveryAndConnectTimer: Timer? = null
    private var discovering = false

    /**
     * Maps from the endpointId to the pipe object handling it
     */
    private val pipes: ConcurrentHashMap<String, Pipe> = ConcurrentHashMap()
    /**
     * Maps from the endpointId to the ourDeviceId (i.e. the "user friendly" name)
     */
    private val deviceNames: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    private fun log(event: LogEvent) {
        logWriter.addLogEntry(event)
        Log.d(TAG, "Event: $event")
    }

    private val mConnectionLifecycleCallback: ConnectionLifecycleCallback = object : ConnectionLifecycleCallback {
        /**
         * Is called when a connection was initiated. The connection has to be accepted to be usable.
         */
        override fun onConnectionInitiated(neighbor: Neighbor, info: ConnectionInfo) {
            val pipe = PipeImplementation(neighbor, ourDeviceId, connectivity, kodein, ::log)

            pipes[neighbor.id] = pipe

            connectivity.acceptConnection(neighbor, pipe.payloadCallback) { success, message ->
                log(ConnectionEvent(ConnectionEvent.Type.AcceptedConnection, neighbor.id, if (!success) "failed: $message" else ""))
            }
        }

        /**
         * Is called when the an initiated connection is either accepted, rejected, or timed out.
         */
        override fun onConnectionResult(neighbor: Neighbor, result: ConnectionResolution) {
            Log.d(TAG, "onConnectionResult($ourDeviceId): $neighbor, $result")
            if (result.status.success) {
                var eventMessage: String? = null
                println("Pipes of $ourDeviceId: $pipes")
                val pipe = pipes[neighbor.id]
                if (pipe != null) {
                    val messageChooser = MessageChooser(kodein, pipe, userId, UserID(neighbor.id))
                    messageChooser.run()
                } else {
                    eventMessage = "No pipe existing for neighbor $neighbor, dropping connection"
                    connectivity.disconnectFromEndpoint(neighbor)
                }
                log(ConnectionEvent(ConnectionEvent.Type.ConnectionEstablished, neighbor.id, additionalInfo = eventMessage
                        ?: ""))
            } else {
                log(ConnectionEvent(ConnectionEvent.Type.ConnectionError, neighbor.id, result.status.statusMessage
                        ?: ""))
                if (!discovering) {
                    startDiscovery()
                }
            }
        }

        /**
         * Is called when the connection to that device is stopped.
         */
        override fun onDisconnected(neighbor: Neighbor) {
            log(ConnectionEvent(ConnectionEvent.Type.Disconnected, neighbor.id))
            if (!discovering) {
                startDiscovery()
            }
        }
    }

    private val mEndpointDiscoveryCallback = object : EndpointDiscoveryCallback {
        override fun onEndpointFound(neighbor: Neighbor, discoveryInfo: DiscoveredEndpointInfo) {
            log(DiscoveryEvent(DiscoveryEvent.Type.Discovered, neighbor.id, "${(neighbor as? NearbyNeighbor)?.nearbyId
                    ?: ""}, service: ${discoveryInfo.serviceId}"))
            initiateConnection()
        }

        override fun onEndpointLost(neighbor: Neighbor) {
            log(DiscoveryEvent(DiscoveryEvent.Type.Lost, neighbor.id))
        }
    }

    private fun advertise() {
        connectivity.startAdvertising(mConnectionLifecycleCallback) { success, optionalMessage ->
            if (!success) {
                log(AdvertisingEvent(AdvertisingEvent.Type.StoppedAdvertising, optionalMessage
                        ?: ""))
            }
        }
        log(AdvertisingEvent(AdvertisingEvent.Type.StartedAdvertising))
    }

    private fun startDiscovery() {
        if (!discovering) {
            connectivity.startDiscovery(mEndpointDiscoveryCallback) { success, message ->
                if (success) {
                    log(DiscoveryEvent(DiscoveryEvent.Type.StartedDiscoverySuccess, "-"))
                    discovering = true
                } else
                    log(DiscoveryEvent(DiscoveryEvent.Type.StartedDiscoveryFailure, "-", message
                            ?: ""))
            }
        } else {
            Log.d(TAG, "already discovering")
        }
    }

    private fun createTimer(): Timer = timer(timerName, false, timerPeriod, timerPeriod) {
        Log.d(TAG, "Timer run: connected: ${connectivity.getConnectedNeighbors()}, discovering: $discovering")
        if (connectivity.getConnectedNeighbors().isEmpty() && !discovering)
            startDiscovery()
        initiateConnection()
    }

    private fun stopDiscovery() {
        connectivity.stopDiscovery()
        discovering = false
        log(DiscoveryEvent(DiscoveryEvent.Type.StoppedDiscovery, ourDeviceId))
    }

    private fun stopAdvertising() {
        connectivity.stopAdvertising()
        log(AdvertisingEvent(AdvertisingEvent.Type.StoppedAdvertising))
    }

    /**
     * Tries to create a new connection
     */
    private fun initiateConnection() {
        if (connectivity.getConnectedNeighbors().isEmpty()) {
            val neighbors = connectivity.getNeighbors()
            if (neighbors.isNotEmpty()) {
                val next = pickNextNeighbor(neighbors)
                if (next != null)
                    connect(next)
            }
        }
    }

    private fun connect(neighbor: Neighbor) {
        val otherName = neighbor.id
        deviceNames[neighbor.id] = otherName
        stopDiscovery()
        connectivity.requestConnection(
                neighbor,
                mConnectionLifecycleCallback) { success, message ->
            if (!success) {
                log(ConnectionEvent(ConnectionEvent.Type.ConnectionError, neighbor.id, "The connection could not be requested: $message"))
                if (!discovering)
                    startDiscovery()
            }
        }
        log(ConnectionEvent(ConnectionEvent.Type.RequestingConnection, neighbor.id, "real name: $otherName"))
    }

    private fun pickNextNeighbor(neighbors: List<Neighbor>): Neighbor? {
        require(neighbors.isNotEmpty(), { "There has to be at least one neighbor to be picked" })
        val validNeighbors = neighbors.filter { it.id > ourDeviceId }
        if (validNeighbors.isEmpty())
            return null
        val randomIndex = random.nextInt(validNeighbors.size)
        return validNeighbors[randomIndex]
    }

    override fun start() {
        connectivity.initiate()
        advertise()
        startDiscovery()
        if (discoveryAndConnectTimer == null) {
            discoveryAndConnectTimer = createTimer()
        }
    }

    /**
     * Terminates all open connections, stops advertising, and removes all callbacks.
     */
    override fun stop() {
        discoveryAndConnectTimer?.cancel()
        discoveryAndConnectTimer = null
        connectivity.stopAllEndpoints()
        stopAdvertising()
        stopDiscovery()
        connectivity.close()
    }
}

class PipeImplementation(override val neighbor: Neighbor, private val ownDeviceId: String, private val connectivity: Connectivity, val kodein: Kodein, val log: (logEvent: LogEvent) -> Unit) : Pipe {
    companion object {
        private val TAG = PipeImplementation::class.java.simpleName
        private const val BUFFER_SIZE = 5000
    }

    private var observer: Pipe.PipeObserver? = null
    private val messageQueue: ConcurrentLinkedQueue<Message> = ConcurrentLinkedQueue()
    private var isDone = false
    private var otherDone = false
    private var isClosed = false

    private val messageSerializer: MessageSerializer = kodein.instance()

    val payloadCallback = object : PayloadCallback {
        /**
         * Will be invoked by the implementation once the payload is transferred completely and successfully.
         */
        override fun onPayloadReceived(neighbor: Neighbor, payload: Payload) {
            val bytes = payload.getBytes()
            addIncomingMessage(bytes)
        }

        /**
         * Will currently only be invoked once, when the payload is received.
         */
        override fun onPayloadTransferUpdate(neighbor: Neighbor, transferUpdate: PayloadTransferUpdate) {
            /* left empty intentionally */
        }
    }

    /**
     * Sets the observer of the pipe, to be notified by incoming messages or errors.
     */
    override fun setObserver(observer: Pipe.PipeObserver) {
        check(!isClosed, { "Cannot interact with a closed pipe" })
        this.observer = observer
        while (messageQueue.isNotEmpty()) {
            observer.onMessageReceive(messageQueue.remove())
        }
    }

    /**
     * Transfer a message to the other side. If the underlying layers fail to do that, false is returned. The method is blocking.
     */
    override fun pushMessage(message: Message) {
        log(MessageEvent(MessageEvent.Type.MessageSent, ownDeviceId, neighbor.id, message::class.java.simpleName, (message as? AlgorithmContentMessage)?.id))
        check(observer != null, { "Cannot send a message before an observer is set." })
        check(!isDone, { "Cannot send messages, after 'signalDone()' was called." })
        check(!isClosed, { "Cannot interact with a closed pipe" })
        // convert to json, then to byte array
        // push to other endpoint
        val messageBytes = messageSerializer.pipeMessageToBytes(PipeContentMessage(message))
        connectivity.sendPayload(neighbor, connectivity.createPayload(compressBytes(messageBytes))) { successful, additionalInfo ->
            observer?.messageDeliveryResult(message, if (successful) Pipe.DeliveryResult.Success else Pipe.DeliveryResult.Failure)
            if (!successful)
                log(MessageEvent(MessageEvent.Type.MessageSendingFailed, ownDeviceId, neighbor.id, message::class.java.simpleName, (message as? AlgorithmContentMessage)?.id, additionalInfo = additionalInfo
                        ?: ""))
        }
    }

    /**
     * To be called by MessageChoosers when they have no more messages to transfer
     */
    override fun signalDone() {
        println("$ownDeviceId: signal done called")
        check(!isClosed, { "Cannot interact with a closed pipe" })
        isDone = true
        connectivity.sendPayload(neighbor, connectivity.createPayload(compressBytes(messageSerializer.pipeMessageToBytes(PipeSignalDoneMessage())))) { _, _ -> }
        log(MessageEvent(MessageEvent.Type.MessageSent, ownDeviceId, neighbor.id, PipeSignalDoneMessage::class.java.simpleName))
        checkToClose()
    }

    /**
     * Removes the observer and forces to not accept new messages anymore. The counterpart will not be able to send messages anymore.
     */
    override fun close() {
        println("Closed pipe")
        observer?.onPipeCompleted() // Observer can be null if setObserver is not called
        isDone = true
        isClosed = true
        observer = null
        if (ownDeviceId > neighbor.id)
            connectivity.disconnectFromEndpoint(neighbor)
    }

    private fun addIncomingMessage(rawMessage: ByteArray) {
        // convert to JSON, then to appropriate message type
        if (!isClosed) {
            try {
                val message = messageSerializer.bytesToPipeMessage(decompressBytes(rawMessage))
                log(MessageEvent(MessageEvent.Type.MessageReceived, neighbor.id, ownDeviceId, message.javaClass.simpleName,
                        ((message as? PipeContentMessage)?.message as? AlgorithmContentMessage)?.id, additionalInfo = (message as? PipeContentMessage)?.message?.javaClass?.simpleName
                        ?: ""))
                val obs = observer
                Log.d(TAG, "$this.observer: $obs")
                when (message) {
                    is PipeContentMessage ->
                        if (obs == null) {
                            messageQueue.add(message.message)
                        } else {
                            obs.onMessageReceive(message.message)
                        }
                    is PipeSignalDoneMessage -> {
                        otherDone = true
                        checkToClose()
                    }
                }
            } catch (e: SerializationException) {
                Log.d(TAG, "Received a message that couldn't be de-serialized.", e)
            } catch (e: DataFormatException) {
                Log.e(TAG, "Received bytes that couldn't be decompressed")
            }
        }
    }

    private fun checkToClose() {
        if (isDone && otherDone) {
            close()
        }
    }

    /**
     * Compresses the given [bytes]
     */
    private fun compressBytes(bytes: ByteArray): ByteArray = Deflater(1).run {
        setInput(bytes)
        finish()
        val output = ByteArray(bytes.size)
        val size = deflate(output)
        end()
        output.sliceArray(0 until size)
    }

    /**
     * Decompresses the given [bytes].
     * @throws DataFormatException if the bytes couldn't be decompressed
     */
    private fun decompressBytes(bytes: ByteArray): ByteArray = Inflater().run {
        setInput(bytes)
        var output: ByteArray
        var size: Int
        val outputList = mutableListOf<ByteArray>()

        do {
            output = ByteArray(BUFFER_SIZE)
            size = inflate(output)
            if (size > 0) {
                outputList.add(output.sliceArray(0 until size))
            }
        } while (size > 0)
        end()
        val result = ByteArray(outputList.map { it.size }.sum())
        outputList.forEachIndexed { index, bytes ->
            System.arraycopy(bytes, 0, result, index * BUFFER_SIZE, bytes.size)
        }
        result
    }
}

sealed class PipeMessage

data class PipeContentMessage(val message: Message) : PipeMessage()

sealed class PipeProtocolMessage : PipeMessage()

class PipeSignalDoneMessage : PipeProtocolMessage() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return true
    }

    override fun hashCode(): Int {
        return javaClass.hashCode()
    }
}

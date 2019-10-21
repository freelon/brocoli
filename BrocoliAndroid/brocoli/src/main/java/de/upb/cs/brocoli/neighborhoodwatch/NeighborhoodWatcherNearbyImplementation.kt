package de.upb.cs.brocoli.neighborhoodwatch

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.instance
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import de.upb.cs.brocoli.library.BrocoliPriority
import de.upb.cs.brocoli.library.UserID
import de.upb.cs.brocoli.model.AlgorithmContentMessage
import de.upb.cs.brocoli.model.Message
import de.upb.cs.brocoli.model.Pipe
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

class NeighborhoodWatcherNearbyImplementation(kodein: Kodein, userId: UserID, private val adHocServiceName: String) : NeighborhoodWatcher {
    companion object {
        private val TAG = NeighborhoodWatcherNearbyImplementation::class.java.simpleName
    }

    private val logWriter: NeighborhoodWatcherLog = kodein.instance()

    private val p2pStrategy: Strategy = kodein.instance()

    private val applicationContext: Context = kodein.instance()
    private val connectionsClient = Nearby.getConnectionsClient(applicationContext)
    private var isAdvertising = false
    private var isDiscovering = false
    private val endpointName = userId.id

    /**
     * Maps from the endpointId to the pipe object handling it
     */
    private val pipes: ConcurrentHashMap<String, NearbyPipe> = ConcurrentHashMap()
    /**
     * Maps from the endpointId to the endpointName (i.e. the "user friendly" name)
     */
    private val deviceNames: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    private fun log(event: LogEvent) {
        logWriter.addLogEntry(event)
        Log.d(TAG, "Event: $event, isAdvertising=$isAdvertising, isDiscovering=$isDiscovering")
    }

    private val mConnectionLifecycleCallback: ConnectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.i(TAG, "onConnectionInitiated: accepting connection")

            val pipe = NearbyPipe(connectionInfo.endpointName, endpointId, connectionsClient, kodein)
            log(ConnectionEvent(ConnectionEvent.Type.AcceptedConnection, endpointId))

            // begin section
            // The following lines are just a hack for testing purposes!
            pipe.setObserver(object : Pipe.PipeObserver {
                /**
                 * Is called once for every message that is pushed in from the other side of the pipe.
                 */
                override fun onMessageReceive(message: Message) {
                    Log.e(TAG, "Received a message from ${connectionInfo.endpointName}: $message")
                    if (message is AlgorithmContentMessage)
                        Toast.makeText(applicationContext, "Got message from ${connectionInfo.endpointName}: '${message.content}'", Toast.LENGTH_SHORT).show()
                }

                /**
                 * Is called when the pipe was closed because of an error
                 */
                override fun onPipeBroken() {
                    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                }

                /**
                 * Called when the pipe is closed without an error (i.e. both parties have signaled they are done)
                 */
                override fun onPipeCompleted() {
                    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                }

                /**
                 * Indicates when sending of a message is over. The result shows whether is was successful or not.
                 */
                override fun messageDeliveryResult(message: Message, result: Pipe.DeliveryResult) {
                    Log.d(TAG, "${if (result == Pipe.DeliveryResult.Success) "Successfully sent" else "Failed to send"} message $message")
                }

            })
            pipes[endpointId] = pipe
            // end section

            connectionsClient.acceptConnection(endpointId, pipe.payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                log(ConnectionEvent(ConnectionEvent.Type.ConnectionEstablished, endpointId))
                pipes[endpointId]?.pushMessage(AlgorithmContentMessage("id123", endpointName, deviceNames[endpointId]!!, 123, Date().time, 2,BrocoliPriority.High,"Hello, ${deviceNames[endpointId]}".toByteArray().toTypedArray()))
                pipes[endpointId]?.signalDone()
            } else {
                log(ConnectionEvent(ConnectionEvent.Type.ConnectionError, endpointId, result.status.statusMessage
                        ?: ""))
                // TODO unwind this further to get the details of why the connection attempt failed!
            }
        }

        override fun onDisconnected(endpointId: String) {
            log(ConnectionEvent(ConnectionEvent.Type.Disconnected, endpointId))
        }
    }

    private val mEndpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, discoveredEndpointInfo: DiscoveredEndpointInfo) {
            val otherName = discoveredEndpointInfo.endpointName
            log(DiscoveryEvent(DiscoveryEvent.Type.Discovered, endpointId, "real name: $otherName"))
            deviceNames[endpointId] = otherName
            if (otherName > endpointName) {
                stopDiscovery()
                connectionsClient.requestConnection(
                        endpointName,
                        endpointId,
                        mConnectionLifecycleCallback)
                        .addOnSuccessListener {
                            // We successfully requested a connection. Now both sides
                            // must accept before the connection is established.
                            //log(ConnectionEvent(ConnectionEvent.Type., endpointId))
                        }
                        .addOnFailureListener {
                            // Nearby Connections failed to request the connection.
                            log(ConnectionEvent(ConnectionEvent.Type.ConnectionError, endpointId, "The connection could not be requested: ${it.message}"))
                        }
                log(ConnectionEvent(ConnectionEvent.Type.RequestingConnection, endpointId, "real name: $otherName"))
            }
        }

        override fun onEndpointLost(endpointId: String) {
            log(DiscoveryEvent(DiscoveryEvent.Type.Lost, endpointId))
        }
    }

    private fun advertise() {
        isAdvertising = true
        connectionsClient.startAdvertising(endpointName, adHocServiceName, mConnectionLifecycleCallback, AdvertisingOptions.Builder().setStrategy(p2pStrategy).build())
        log(AdvertisingEvent(AdvertisingEvent.Type.StartedAdvertising))
    }

    private fun discoverAndConnect() {
        isDiscovering = true
        connectionsClient.startDiscovery(adHocServiceName,
                mEndpointDiscoveryCallback,
                DiscoveryOptions.Builder().setStrategy(p2pStrategy).build()).addOnCompleteListener {
            log(DiscoveryEvent(DiscoveryEvent.Type.StartedDiscoverySuccess, "-"))
        }.addOnFailureListener {
            log(DiscoveryEvent(DiscoveryEvent.Type.StartedDiscoveryFailure, "-", it.message ?: ""))
        }
    }

    private fun stopDiscovery() {
        isDiscovering = false
        connectionsClient.stopDiscovery()
    }

    override fun start() {
        advertise()
        discoverAndConnect()
    }

    /**
     * Terminates all open connections, stops advertising, and removes all callback.
     */
    override fun stop() {
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        log(AdvertisingEvent(AdvertisingEvent.Type.StoppedAdvertising))
        connectionsClient.stopDiscovery()
        log(DiscoveryEvent(DiscoveryEvent.Type.StoppedDiscovery, "-"))
    }
}

class NearbyPipe(private val otherId: String, private val endpointId: String, private val connectionsClient: ConnectionsClient, val kodein: Kodein) {
    companion object {
        private val TAG = NearbyPipe::class.java.simpleName
        private const val BUFFER_SIZE = 5000
    }

    private var observer: Pipe.PipeObserver? = null
    private val messageQueue: ConcurrentLinkedQueue<Message> = ConcurrentLinkedQueue()
    private var isDone = false
    private var otherDone = false
    private var isClosed = false

    private val messageSerializer: MessageSerializer = kodein.instance()

    val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes()
                if (bytes != null)
                    addIncomingMessage(bytes)
            } else {
                Log.e(TAG, "Unhandled incoming payload type (type = ${payload.type}) from $endpointId")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    /**
     * Sets the observer of the pipe, to be notified by incoming messages or errors.
     */
    fun setObserver(observer: Pipe.PipeObserver) {
        check(!isClosed, { "Cannot interact with a closed pipe" })
        this.observer = observer
        while (messageQueue.isNotEmpty()) {
            observer.onMessageReceive(messageQueue.remove())
        }
    }

    /**
     * Transfer a message to the other side. If the underlying layers fail to do that, false is returned. The method is blocking.
     */
    fun pushMessage(message: Message) {
        check(observer != null, { "Cannot send a message before an observer is set." })
        check(!isDone, { "Cannot send messages, after 'signalDone()' was called." })
        check(!isClosed, { "Cannot interact with a closed pipe" })
        // convert to json, then to byte array
        // push to other endpoint
        val messageBytes = messageSerializer.pipeMessageToBytes(PipeContentMessage(message))
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(compressBytes(messageBytes))).addOnCompleteListener {
            observer?.messageDeliveryResult(message, if (it.isSuccessful) Pipe.DeliveryResult.Success else Pipe.DeliveryResult.Failure)
        }
    }

    /**
     * To be called by MessageChoosers when they have no more messages to transfer
     */
    fun signalDone() {
        check(!isClosed, { "Cannot interact with a closed pipe" })
        isDone = true
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(compressBytes(messageSerializer.pipeMessageToBytes(PipeSignalDoneMessage()))))
        checkToClose()
    }

    /**
     * Removes the observer and forces to not accept new messages anymore. The counterpart will not be able to send messages anymore.
     */
    fun close() {
        isDone = true
        isClosed = true
        //Pipe is closed, call back pipe Completed interface
        observer!!.onPipeCompleted() //Observer can be null if setObserver is not called
        observer = null
        if (endpointId > otherId)
            connectionsClient.disconnectFromEndpoint(endpointId)
    }

    private fun addIncomingMessage(rawMessage: ByteArray) {
        // convert to JSON, then to appropriate message type
        if (!isClosed) {
            try {
                val message = messageSerializer.bytesToPipeMessage(decompressBytes(rawMessage))
                Log.d(TAG, "Received message of type ${message.javaClass.simpleName}")
                val obs = observer
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
        } while (size == 100)
        end()
        val result = ByteArray(outputList.map { it.size }.sum())
        outputList.forEachIndexed { index, bytes ->
            System.arraycopy(bytes, 0, result, index * BUFFER_SIZE, bytes.size)
        }
        result
    }
}

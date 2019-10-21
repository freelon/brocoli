package de.upb.cs.brocoliserver.neighborhoodwatch

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.instance
import de.upb.cs.brocoliserver.Log
import de.upb.cs.brocoliserver.connectivity.*
import de.upb.cs.brocoliserver.library.LogEvent
import de.upb.cs.brocoliserver.library.MessageEvent
import de.upb.cs.brocoliserver.library.MessageSerializer
import de.upb.cs.brocoliserver.library.SerializationException
import de.upb.cs.brocoliserver.model.AlgorithmContentMessage
import de.upb.cs.brocoliserver.model.Message
import de.upb.cs.brocoliserver.model.Pipe
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater


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
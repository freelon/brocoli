package de.upb.cs.brocoliserver.standalone

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.bind
import com.github.salomonbrys.kodein.instance
import com.github.salomonbrys.kodein.singleton
import de.upb.cs.brocoliserver.Log
import de.upb.cs.brocoliserver.connectivity.InputStreamWrapper
import de.upb.cs.brocoliserver.connectivity.JsonMessageSerializer
import de.upb.cs.brocoliserver.connectivity.Neighbor
import de.upb.cs.brocoliserver.connectivity.OutputStreamWrapper
import de.upb.cs.brocoliserver.library.*
import de.upb.cs.brocoliserver.model.*
import de.upb.cs.brocoliserver.neighborhoodwatch.PipeContentMessage
import de.upb.cs.brocoliserver.neighborhoodwatch.PipeSignalDoneMessage
import kotlinx.coroutines.experimental.launch
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.DataFormatException


fun main(args: Array<String>) {
    RobustCommunicationServer("rkserver", 9099).run()
}

class RobustCommunicationServer(serverId: String, private val port: Int, messageRepository: AlgorithmMessageRepository? = null, ackRepository: AckRepository? = null) {
    private val serverUserID = UserID(serverId)
    private val inMemoryRepository = InMemoryRepository()
    private val messageRepository = messageRepository ?: inMemoryRepository
    private val ackRepository = ackRepository ?: inMemoryRepository
    private val kodein = Kodein {
        bind<MessageRouterFactory>() with singleton { AcknowledgingMessageRouterFactory() }
        bind<AlgorithmMessageRepository>() with singleton { this@RobustCommunicationServer.messageRepository }
        bind<AckRepository>() with singleton { this@RobustCommunicationServer.ackRepository }
        bind<MessageSerializer>() with singleton { JsonMessageSerializer() }
    }
    private lateinit var serverSocket: ServerSocket

    companion object {
        private val TAG = RobustCommunicationServer::class.java.simpleName
    }

    /**
     * Used to run the server in the background. The method will return immediately.
     */
    fun start() {
        launch {
            run()
        }
    }

    /**
     * Runs the server blocking on this function.
     */
    fun run() {
        serverSocket = ServerSocket(port)
        Log.i(TAG, "Socket address: ${serverSocket.inetAddress}:${serverSocket.localPort}")
        if (messageRepository is InMemoryRepository)
            Log.e(TAG, "The message repository that is used is an InMemoryRepository - nothing will be persisted!")
        if (ackRepository is InMemoryRepository)
            Log.e(TAG, "The acknowledgement repository that is used is an InMemoryRepository - nothing will be persisted!")

        while (true) {
            val connection = serverSocket.accept()
            launch {
                val isw = InputStreamWrapper(connection.getInputStream())
                val otherId = String(isw.read())
                val neighbor = SocketNeighbor(otherId)
                Log.d(TAG, "New connection from neighbor $neighbor")
                val pipe = SocketPipe(neighbor, serverUserID.id, kodein, connection, ::log)

                MessageChooser(kodein, pipe, serverUserID, UserID(otherId)).run()
            }

        }
    }

    fun stop() {
        serverSocket.close()
    }

    private fun log(event: LogEvent) {
        Log.d(TAG, "Event: $event")
    }
}

class SocketPipe(override val neighbor: Neighbor, private val ownDeviceId: String, kodein: Kodein, val socket: Socket, val log: (logEvent: LogEvent) -> Unit) : Pipe {
    companion object {
        private val TAG = SocketPipe::class.java.simpleName
    }

    private val messageSerializer: MessageSerializer = kodein.instance()
    private var observer: Pipe.PipeObserver? = null
    private val inputStreamWrapper = InputStreamWrapper(socket.getInputStream())
    private val outputStreamWrapper = OutputStreamWrapper(socket.getOutputStream())

    private val messageQueue: ConcurrentLinkedQueue<Message> = ConcurrentLinkedQueue()
    private var isDone = false
    private var otherDone = false
    private var isClosed = false
    var lastInteraction: Long = System.currentTimeMillis()
        private set

    init {
        launch {
            while (!isClosed) {
                try {
                    val content = inputStreamWrapper.read()
                    addIncomingMessage(content)
                } catch (e: IOException) {
                    Log.d(TAG, "reading content failed. closing socket to neighbor $neighbor", e)
                    close()
                }
            }
        }
    }

    override fun setObserver(observer: Pipe.PipeObserver) {
        check(!isClosed) { "Cannot interact with a closed pipe" }
        this.observer = observer
        while (messageQueue.isNotEmpty()) {
            observer.onMessageReceive(messageQueue.remove())
        }
    }

    override fun pushMessage(message: Message) {
        log(MessageEvent(MessageEvent.Type.MessageSent, ownDeviceId, neighbor.id, message::class.java.simpleName, (message as? AlgorithmContentMessage)?.id))
        check(observer != null) { "Cannot send a message before an observer is set." }
        check(!isDone) { "Cannot send messages, after 'signalDone()' was called." }
        check(!isClosed) { "Cannot interact with a closed pipe" }
        lastInteraction = System.currentTimeMillis()
        // convert to json, then to byte array
        // push to other endpoint
        val messageBytes = messageSerializer.pipeMessageToBytes(PipeContentMessage(message))
        try {

            outputStreamWrapper.write(messageBytes)
            observer?.messageDeliveryResult(message, Pipe.DeliveryResult.Success)
        } catch (e: IOException) {
            Log.d(TAG, "Exception while sending message", e)
            observer?.messageDeliveryResult(message, Pipe.DeliveryResult.Failure)
            close()
            observer?.onPipeBroken()
        }
    }

    override fun signalDone() {
        check(!isClosed) { "Cannot interact with a closed pipe" }
        lastInteraction = System.currentTimeMillis()
        isDone = true
        outputStreamWrapper.write(compressBytes(messageSerializer.pipeMessageToBytes(PipeSignalDoneMessage())))
        log(MessageEvent(MessageEvent.Type.MessageSent, ownDeviceId, neighbor.id, PipeSignalDoneMessage::class.java.simpleName))
        checkToClose()
    }

    override fun close() {
        isDone = true
        isClosed = true
        observer = null
        if (ownDeviceId > neighbor.id)
            socket.close()
    }

    private fun addIncomingMessage(rawMessage: ByteArray) {
        lastInteraction = System.currentTimeMillis()

        // convert to JSON, then to appropriate message type
        if (!isClosed) {
            try {
                val message = messageSerializer.bytesToPipeMessage(decompressBytes(rawMessage))
                log(MessageEvent(MessageEvent.Type.MessageReceived, neighbor.id, ownDeviceId, message.javaClass.simpleName,
                        ((message as? PipeContentMessage)?.message as? AlgorithmContentMessage)?.id, additionalInfo = (message as? PipeContentMessage)?.message?.javaClass?.simpleName
                        ?: ""))
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
                Log.d(TAG, "Received a message that couldn't be de-serialized. (${String(rawMessage)})", e)
                close()
                observer?.onPipeBroken()
            } catch (e: DataFormatException) {
                Log.e(TAG, "Received bytes that couldn't be decompressed")
                close()
                observer?.onPipeBroken()
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
    private fun compressBytes(bytes: ByteArray): ByteArray = bytes

    /**
     * Decompresses the given [bytes].
     * @throws DataFormatException if the bytes couldn't be decompressed
     */
    private fun decompressBytes(bytes: ByteArray): ByteArray = bytes
}

class SocketNeighbor(id: String) : Neighbor(id) {
    override fun toString(): String = "SocketNeighbor($id)"
}

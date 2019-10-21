package de.upb.cs.brocoli.library

import android.util.Log
import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.instance
import com.google.common.primitives.Ints
import de.upb.cs.brocoli.connectivity.Neighbor
import de.upb.cs.brocoli.model.AlgorithmContentMessage
import de.upb.cs.brocoli.model.Message
import de.upb.cs.brocoli.model.MessageChooser
import de.upb.cs.brocoli.model.Pipe
import de.upb.cs.brocoli.neighborhoodwatch.*
import kotlinx.coroutines.experimental.launch
import java.io.*
import java.net.Socket
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.DataFormatException
import kotlin.concurrent.timer

/**
 * When run, periodically exchanges messages with the RobustCommunicationServer.
 * If privateServiceIdWhitelist is set, only messages that have a service id listed there are exchanged.
 */
class ServerCommunication(val kodein: Kodein,
                          private val ownDeviceId: UserID,
                          private val serviceIdWhitelist: List<ServiceId>?,
                          private val networkService: NetworkService) {
    companion object {
        private const val TIMER_NAME = "ServerCommunicationTimer"
        private val TAG = ServerCommunication::class.java.simpleName
        const val MASTER_SERVER_ADDRESS_FIELD = "masterServerAddress"
        const val MASTER_SERVER_PORT_FIELD = "masterServerPort"
        const val REFRESH_INTERVAL_SECONDS = "refresh-interval-for-server-communication"
        const val MASTER_SERVER_USER_ID = "master-server-user-id"
    }

    private val host: String = kodein.instance(MASTER_SERVER_ADDRESS_FIELD)
    private val port: Int = kodein.instance(MASTER_SERVER_PORT_FIELD)
    private val serverId = UserID(kodein.instance(MASTER_SERVER_USER_ID))
    private val autoRefreshIntervalSeconds: Int = kodein.instance(REFRESH_INTERVAL_SECONDS)

    private val logWriter: NeighborhoodWatcherLog = kodein.instance()

    private var nextRunTimer: Timer? = null
    private var isRunning = false

    private var lastSocket: Socket? = null
    private var lastPipe: SocketPipe? = null

    fun start() {
        if (isRunning)
            return

        isRunning = true
        nextRunTimer = createTimer()
    }

    /**
     * Stops running a next message exchange. If one is currently running, it is not stopped.
     */
    fun stop() {
        lastSocket?.close()
        nextRunTimer?.cancel()
    }

    private fun runExchange() {
        // clear up old socket, if it hasn't been used for [autoRefreshIntervalSeconds]
        if (System.currentTimeMillis() - (lastPipe?.lastInteraction ?: 0) >= autoRefreshIntervalSeconds * 1000) {
            lastSocket?.close()
            Log.d(TAG, "closed old socket, it wasn't used for more than $autoRefreshIntervalSeconds seconds")
        }

        if (networkService.isOnline()) {
            Log.d(TAG, "running an exchange!")
            if (lastSocket?.isClosed == false) {
                Log.d(TAG, "There is another not-closed socket. Skipping this round.")
                return
            }
            try {
                val socket = Socket(host, port)
                lastSocket = socket
                val osw = OutputStreamWrapper(socket.getOutputStream())
                // according to protocol, the first thing that has to be sent once the connection is opened, is the deviceID
                osw.write(ownDeviceId.id.toByteArray())

                val neighbor = SocketNeighbor(serverId.id)
                val pipe = SocketPipe(neighbor, ownDeviceId.id, kodein, socket, ::log)
                MessageChooser(kodein, pipe, ownDeviceId, serverId, serviceIdWhitelist).run()
            } catch (e: IOException) {
                Log.d(TAG, "Connection to server ($host:$port) not working: ${e.localizedMessage}")
            }
        } else {
            Log.d(TAG, "NetworkService suggests we're not online, so not taking any action.")
        }
    }

    private fun createTimer(): Timer = timer(TIMER_NAME, false, period = (autoRefreshIntervalSeconds * 1000).toLong()) {
        runExchange()
    }

    private fun log(event: LogEvent) {
        logWriter.addLogEntry(event)
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

class OutputStreamWrapper(val os: OutputStream) : Closeable, Flushable {
    companion object {
        val TAG: String = OutputStreamWrapper::class.java.simpleName
    }

    @Throws(IOException::class)
    override fun close() {
        os.close()
    }

    @Throws(IOException::class)
    override fun flush() {
        os.flush()
    }

    @Throws(IOException::class)
    fun write(content: ByteArray) {
        check(content.isNotEmpty()) { "cannot send empty content" }
        Log.d(TAG, "Writing ${content.size} bytes")
        os.write(Ints.toByteArray(content.size))
        os.write(content)
        os.flush()
    }
}

class InputStreamWrapper(val stream: InputStream) : Closeable {
    companion object {
        val TAG: String = InputStreamWrapper::class.java.simpleName
    }

    override fun close() {
        stream.close()
    }

    /**
     * Reads the next available message, as sent in [OutputStreamWrapper.write].
     * If there is no more to read an [EOFException] is thrown.
     * [IOException]s of the underlying [InputStream] are rethrown.
     */
    @Throws(EOFException::class, IOException::class)
    fun read(): ByteArray {
        var length = Ints.BYTES
        val header = ByteArray(length)
        var read = 0
        var newRead: Int
        while (read < length) {
            newRead = stream.read(header, read, length - read)
            if (newRead < 0) {
                throw EOFException()
            }

            read += newRead
        }

        if (read != 4)
            throw IOException("Cannot read from $stream.")

        length = Ints.fromByteArray(header)
        Log.d(TAG, "reading $length bytes")
        if (length < 0) {
            throw EOFException()
        }

        val message = ByteArray(length)
        read = 0
        while (read < length) {
            newRead = stream.read(message, read, length - read)
            if (newRead < 0) {
                throw EOFException()
            }

            read += newRead
        }
        Log.d(TAG, "read ${message.size} bytes")
        return message
    }
}

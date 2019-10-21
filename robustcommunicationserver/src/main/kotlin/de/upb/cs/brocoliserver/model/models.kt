package de.upb.cs.brocoliserver.model

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.instance
import de.upb.cs.brocoliserver.connectivity.Neighbor
import de.upb.cs.brocoliserver.library.MessageBody
import de.upb.cs.brocoliserver.library.BrocoliPriority
import de.upb.cs.brocoliserver.library.ServiceId
import de.upb.cs.brocoliserver.library.UserID
import kotlinx.coroutines.experimental.launch
import java.util.*

interface Message {
    val fromId: String
}

/**
 * Can be used by [MessageChooser]s to exchange protocol information (i.e. the list of known messages)
 */
interface ProtocolMessage : Message {
    override val fromId: String
    val toId: String
}

/**
 * A message used to exchange information about the messages known to a participant
 */
data class ListExchangeMessage(override val fromId: String, override val toId: String, val knownMessageIds: List<String>, val ackMessageIds: List<Ack> = listOf()) : ProtocolMessage

data class AlgorithmContentMessage(
        val id: String,
        override val fromId: String,
        val toId: String,
        val serviceId: ServiceId,
        val timestamp: Long,
        val ttlHours: Byte,
        val priority: BrocoliPriority,
        val content: MessageBody
) : Message {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AlgorithmContentMessage

        if (id != other.id) return false
        if (fromId != other.fromId) return false
        if (toId != other.toId) return false
        if (serviceId != other.serviceId) return false
        if (timestamp != other.timestamp) return false
        if (ttlHours != other.ttlHours) return false
        if (priority != other.priority) return false
        if (!Arrays.equals(content, other.content)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + fromId.hashCode()
        result = 31 * result + toId.hashCode()
        result = 31 * result + serviceId
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + ttlHours
        result = 31 * result + priority.hashCode()
        result = 31 * result + Arrays.hashCode(content)
        return result
    }
}


/** Format of Acknowledgement Messages
 * @param [id] Message ID of current message
 * @param [expiryDate] Time to live for the message
 */
data class Ack(val id: String, val expiryDate: Long)

data class Contact(val id: String, val name: String)

/**
 * To be implemented by whoever creates connections between devices.
 * The [Pipe] buffers messages that arrive while no observer is registered.
 */
interface Pipe {
    val neighbor: Neighbor

    enum class DeliveryResult {
        Success, Failure
    }

    /**
     * Should be implemented by whatever class receives connections and handles message exchange with other side.
     */
    interface PipeObserver {
        /**
         * Is called once for every message that is pushed in from the other side of the pipe.
         */
        fun onMessageReceive(message: Message)

        /**
         * Is called when the pipe was closed because of an error
         */
        fun onPipeBroken()

        /**
         * Called when the pipe is closed without an error (i.e. both parties have signaled they are done)
         */
        fun onPipeCompleted()

        /**
         * Indicates when sending of a message is over. The result shows whether is was successful or not.
         */
        fun messageDeliveryResult(message: Message, result: DeliveryResult)
    }

    /**
     * Sets the observer of the pipe, to be notified by incoming messages or errors.
     */
    fun setObserver(observer: PipeObserver)

    /**
     * Transfer a message to the other side. If the underlying layers fail to do that, false is returned. The method is blocking.
     */
    fun pushMessage(message: Message)

    /**
     * To be called by MessageChoosers when they have no more messages to transfer
     */
    fun signalDone()

    /**
     * Removes the observer and forces to not accept new messages anymore. Incoming messages will be thrown away.
     */
    fun close()
}

interface MessageRouter {

    fun sendMessages()
}


/**
 * Is invoked for every connection with a neighbor and responsible for exchanging messages with a neighbor in a smart way
 * @constructor Uses [kodein] to create dependency injection for chatRepository and [pipe] to send/Receive messages with another device.
 */
class MessageChooser(private val kodein: Kodein, private val pipe: Pipe, private val ownID: UserID, private val neighborID: UserID) {

    /**
     * Run a new thread to send Messages in Chat Repository and add a observer to listen to incoming messages.
     */
    fun run() {
        val messageRouter: MessageRouter = kodein.instance<MessageRouterFactory>().create(kodein, pipe, ownID, neighborID)
        launch {
            //val messageRouter: MessageRouter = kodein.instance()
            messageRouter.sendMessages()
        }
    }
}

interface MessageRouterFactory {
    fun create(kodein: Kodein, pipe: Pipe, ownID: UserID, neighborID: UserID): MessageRouter
}

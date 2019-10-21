package de.upb.cs.brocoli.model

import android.util.Log
import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.instance
import de.upb.cs.brocoli.library.ServiceId
import de.upb.cs.brocoli.library.UserID
import kotlinx.coroutines.experimental.launch
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * New class to support Message Routing.
 * @constructor creates an empty class.
 * @param [kodein] Repository object containing list of chat messages.
 * @param [pipe] Interface used to push messages and signal when transfer is complete.
 * @param [ownID] identifier of our device.
 * @param [neighborID] identifier of the neighbor to which the messages are exchanged.
 */
class SimpleMessageRouter(private val kodein: Kodein, private val pipe: Pipe, private val ownID: UserID, private val neighborID: UserID, private val serviceIdWhitelist: List<ServiceId>? = null) : MessageRouter {
    private val algorithmMessageRepository: AlgorithmMessageRepository = kodein.instance()
    private var neighbourIDList: List<String>
    private var messageQueue: Queue<AlgorithmContentMessage>
    private val tag = this.javaClass.simpleName
    private val hasClosed = AtomicBoolean(false)

    /**
     * Add pipe observer to pipe class,  initialize known id list and message queue which would track messages that need to be sent.
     */
    init {
        addPipeObserver()
        neighbourIDList = listOf() //Initialize Empty List
        messageQueue = ArrayDeque<AlgorithmContentMessage>()
    }

    /**
     * Function to send single message through pipe.
     * @param [message] Message of Type [AlgorithmContentMessage] or [ListExchangeMessage].
     */
    private fun send(message: Message) {
        if (!hasClosed.get()) {
            try {
                pipe.pushMessage(message)
            } catch (e: IllegalStateException) {
                Log.e(tag, "Tried to illegally send a message", e)
            }
        } else {
            println("Router(${ownID.id}): trying to send a message after closing the pipe")
        }
    }

    /**
     * Function which gets executed when call back for list messages is received.
     * Pass list of all missing messages to the other end.
     */
    @Synchronized
    fun compareKnownMessagesAndSend() {
        messageQueue = findMessagesToSend()
        if (messageQueue.isEmpty()) {
            if (!hasClosed.getAndSet(true))
                pipe.signalDone()
            return
        }
        val message = messageQueue.poll()
        send(message)
    }


    /**
     * called during object creation.
     * pass list of all chat Id's to the connected neighbor.
     */
    @Synchronized
    override fun sendMessages() {
        val messageList: MutableList<String> = algorithmMessageRepository.getAllMessagesAsList().asSequence().map { it.id }.toMutableList()
        val listExchangeMessage = ListExchangeMessage(ownID.id, neighborID.id, messageList)
        send(listExchangeMessage)
    }

    /**
     * Function to identify the messages which need to be exchanged.
     * Compare messages in [algorithmMessageRepository] and neighbour id list.
     * Find the difference between the two lists.
     * @return List of chat messages to be sent.
     */
    @Synchronized
    private fun findMessagesToSend(): Queue<AlgorithmContentMessage> {
        val currentTimeStamp = Date().time
        return ArrayDeque(algorithmMessageRepository.getAllMessagesAsList().asSequence()
                .filter { it.id !in this.neighbourIDList }
                .filter { it.timestamp + it.ttlHours * 3600_000 > currentTimeStamp }
                .filter { serviceIdWhitelist == null || it.serviceId in serviceIdWhitelist }
                .toList())
    }

    /**
     * Create a new [Pipe.PipeObserver] object and add it to [Pipe] class.
     * Function used to call back when messages are received in [Pipe.PipeObserver].
     * When a [ListExchangeMessage] is received, difference between own and neighbour's repository would be sent as a set of new messages.
     * When a [AlgorithmContentMessage] is received, it would be added to own [algorithmMessageRepository].
     */
    @Synchronized
    private fun addPipeObserver() {
        pipe.setObserver(object : Pipe.PipeObserver {
            @Synchronized
            override fun onMessageReceive(message: Message) {
                when (message) {
                    is ListExchangeMessage -> {
                        neighbourIDList = message.knownMessageIds
                        launch {
                            compareKnownMessagesAndSend()
                        }
                    }
                    is AlgorithmContentMessage ->
                        algorithmMessageRepository.add(message)
                }
            }

            @Synchronized
            override fun onPipeBroken() {

            }

            @Synchronized
            override fun onPipeCompleted() {

            }

            @Synchronized
            override fun messageDeliveryResult(message: Message, result: Pipe.DeliveryResult) {
                Log.d(tag, "message delivery result for $message: $result")
                if (result != Pipe.DeliveryResult.Success) {
                    Log.e(tag, "Message Delivery failed. Check Pipe Status")
                }
                if (message is AlgorithmContentMessage) {
                    /*
                    Don't attempt to close after sending something other than a AlgorithmContentMessage.
                    The following could happen: we send our ListExchangeMessage and haven't received
                    the one of the neighbor. Therefore we have an empty queue and close, before even
                    knowing whether we should send something.
                     */
                    if (messageQueue.isEmpty()) {
                        if (!hasClosed.getAndSet(true))
                            pipe.signalDone()
                        return
                    }
                }
                val message = messageQueue.poll()
                if (message != null)
                    send(message)
            }
        })
    }
}

class SimpleMessageRouterFactory : MessageRouterFactory {
    override fun create(kodein: Kodein, pipe: Pipe, ownID: UserID, neighborID: UserID, serviceIdWhitelist: List<ServiceId>?): MessageRouter {
        return SimpleMessageRouter(kodein, pipe, ownID, neighborID, serviceIdWhitelist)
    }
}


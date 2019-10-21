package de.upb.cs.brocoliserver.model


import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.instance
import de.upb.cs.brocoliserver.Log
import de.upb.cs.brocoliserver.library.UserID
import kotlinx.coroutines.experimental.launch
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean


/**
 * New class to support Message Routing.
 * @constructor creates an empty class.
 * @param [kodein] dependency Injection object for chat Repository , Ack Repository
 * @param [pipe] Interface used to push messages and signal when transfer is complete.
 * @param [ownID] identifier of our device.
 * @param [neighborID] identifier of the neighbor to which the messages are exchanged.
 */
class AcknowledgingMessageRouter(private val kodein: Kodein, private val pipe: Pipe, private val ownID: UserID, private val neighborID: UserID) : MessageRouter {

    private val algorithmMessageRepository: AlgorithmMessageRepository = kodein.instance()
    private val ackRepository: AckRepository = kodein.instance()
    private var neighbourIDList: List<String>
    private var messageQueue: Queue<AlgorithmContentMessage>
    private val tag = this.javaClass.simpleName
    private val hasClosed = AtomicBoolean(false)

    /**
     * Add pipe observer to pipe class,  initialize known id list and message queue which would track messages that need to be sent.
     */
    init {
        addPipeObserver()
        neighbourIDList = listOf()
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
        val currentTimeStamp = Date().time
        val algorithmMessageList: MutableList<String> = algorithmMessageRepository.getAllMessages()
                .filter { it.timestamp + it.ttlHours * 3600_000 > currentTimeStamp }
                .map { it.id }.toMutableList()
        val ackMessages = ackRepository.getAcknowledgementsAsList().filter { it.expiryDate < currentTimeStamp }
        val listExchangeMessage = ListExchangeMessage(ownID.id, neighborID.id, algorithmMessageList, ackMessages)
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
        return ArrayDeque(algorithmMessageRepository.getAllMessages().filter { it.id !in this.neighbourIDList }.filter { it.timestamp + it.ttlHours * 3600_000 > currentTimeStamp })
    }

    /**
     * Function to add Messages to [AckRepository].
     * @param [algorithmContentMessage] : [AlgorithmContentMessage] to be added to [AckRepository]
     */
    @Synchronized
    private fun addMessageToAckRepository(algorithmContentMessage: AlgorithmContentMessage) {
        ackRepository.add(Ack(algorithmContentMessage.id,
                algorithmContentMessage.timestamp + algorithmContentMessage.ttlHours * 3_600_000))
        algorithmMessageRepository.deleteByIds(listOf(algorithmContentMessage.id))
    }

    /**
     * Function to add all Messages to [AckRepository].
     * @param acks : [Ack] Messages to be added
     */
    @Synchronized
    private fun addAllAckMessages(acks: List<Ack>) = ackRepository.addAll(acks)

    /**
     * Function to remove all Messages to [algorithmMessageRepository].
     * @param [acks] : [Ack] Messages to be deleted from [algorithmMessageRepository]
     */
    @Synchronized
    private fun removeFromChatRepository(acks: List<String>) = algorithmMessageRepository.deleteByIds(acks)

    /**
     * Create a new [Pipe.PipeObserver] object and add it to [Pipe] class.
     * Function used to call back when messages are received in [Pipe.PipeObserver].
     * When a [ListExchangeMessage] is received, difference between own and neighbour's repository would be sent as a set of new messages.
     * When a [AlgorithmContentMessage] is received, it would be added to own [algorithmMessageRepository].
     */
    @Synchronized
    private fun addPipeObserver() {
        pipe.setObserver(object : Pipe.PipeObserver {
            /**
             * If we get a [ListExchangeMessage] we use it to a) update our acknowledgements and
             * b) see which messages the other party has. Then we automatically start sending
             * all messages that are not yet acknowledged and the other party doesn't posses.
             * If we receive an [AlgorithmContentMessage] we store it in our repositories. In case it
             * is targeted at us, we also create an acknowledgement for it.
             */
            @Synchronized
            override fun onMessageReceive(message: Message) {
                when (message) {
                    is ListExchangeMessage -> {
                        neighbourIDList = message.knownMessageIds
                        launch {
                            addAllAckMessages(message.ackMessageIds)
                            removeFromChatRepository(message.ackMessageIds.map { it.id })
                            compareKnownMessagesAndSend()
                        }
                    }
                    is AlgorithmContentMessage -> {
                        algorithmMessageRepository.add(message)
                        if (message.toId == ownID.id) {
                            addMessageToAckRepository(message)
                        }
                    }
                }
            }

            @Synchronized
            override fun onPipeBroken() {

            }

            @Synchronized
            override fun onPipeCompleted() {

            }

            /**
             * This is called whenever sending a message from us to another party is finished.
             * If the message was delivered successfully, was an AlgorithmContentMessage and targeted
             * towards the other party, we create an acknowledgement for it.
             */
            @Synchronized
            override fun messageDeliveryResult(message: Message, result: Pipe.DeliveryResult) {

                if (result != Pipe.DeliveryResult.Success) {
                    Log.e(tag, "Message Delivery failed. Check Pipe Status")
                } else {
                    if (message is AlgorithmContentMessage) {
                        if (message.toId == neighborID.id)
                            addMessageToAckRepository(message)
                    }
                }

                /* if the previous message was an AlgorithmContentMessage we are in the mode of sending
                all messages we know the other party doesn't posses, and we will keep sending those until
                there are non left.
                 */
                if (message is AlgorithmContentMessage) {
                    if (messageQueue.isEmpty()) {
                        if (!hasClosed.getAndSet(true))
                            pipe.signalDone()
                        return
                    } else {

                        val nextMessage = messageQueue.poll()
                        if (nextMessage != null)
                            send(nextMessage)
                    }
                }
            }
        })
    }

}

class AcknowledgingMessageRouterFactory : MessageRouterFactory {
    override fun create(kodein: Kodein, pipe: Pipe, ownID: UserID, neighborID: UserID): MessageRouter {
        return AcknowledgingMessageRouter(kodein, pipe, ownID, neighborID)
    }
}

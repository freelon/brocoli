package de.upb.cs.brocoli.library

import com.google.common.primitives.Longs
import de.upb.cs.brocoli.database.util.toBase64String
import java.security.MessageDigest
import java.util.*


/**
 * A wrapper for user ids, so that they can be checked and have a specified format
 */
data class UserID(val id: String) {
    init {
        if (id.any { !it.isLetterOrDigit() })
            throw IllegalArgumentException("The id can only contain letters and digits. '$id' doesn't.")
        if (id.isEmpty())
            throw IllegalArgumentException("The id cannot be empty.")
    }
}

const val BROADCAST_USER_ID = "broadcast"

typealias ServiceId = Byte

typealias MessageBody = Array<Byte>

enum class BrocoliPriority {
    Low, High, Severe
}

class BrocoliMessage(
        val from: UserID,
        val to: UserID,
        val serviceId: ServiceId,
        val timestamp: Long = Date().time,
        val ttlHours: Byte,
        val priority: BrocoliPriority,
        val messageBody: MessageBody,
        id: String? = null
) {
    val id = id ?: secureHash()

    private fun secureHash(): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(from.id.toByteArray())
        md.update(to.id.toByteArray())
        md.update(serviceId)
        md.update(Longs.toByteArray(timestamp))
        md.update(ttlHours)
        md.update(priority.name.toByteArray())
        md.update(messageBody.toByteArray())
        val digest: ByteArray = md.digest()
        return digest.toBase64String()
    }
}

/**
 *
 */
interface ServiceCallback {
    fun messageArrived(message: BrocoliMessage)
}

/**
 * Any service interested in sending or receiving messages should use this interface to register
 * itself.
 */
interface ServiceHandler {
    /**
     * Removes all references to callbacks of the given service id
     */
    fun unregisterService(serviceId: ServiceId)

    /**
     * Sends a message to the specified user. If the recipient is available online, it will be sent
     * using online services, otherwise it will be stored and sent using the best available option
     * coming up in the future.
     */
    @Throws(OnlineCommunicationException::class)
    fun sendMessage(message: BrocoliMessage, sendOnlineOnly: Boolean): BrocoliMessage?

    /**
     * Registers a [ServiceCallback] for a service id. Any new messages arriving with that specific
     * service id will be passed to that ServiceCallback. The initializing class is a class that
     * registers this callback handler when initialized.
     */
    fun registerService(serviceId: Byte, callbackHandler: ServiceCallback, serviceName: String?,
                        serviceOnlineHandler: ServiceOnlineHandler, onlineHandlerId: UserID?,
                        shouldExchangeWithServer: Boolean = false)

    fun fetchMessagesFromServer(serviceId: Byte)
}

/**
 * A [ServiceOnlineHandler] can exchange messages for a service with an online server. If a service
 * doesn't need that, it should overwrite [willHandleMessages] to return false.
 */
interface ServiceOnlineHandler {
    /**
     * Handles the given message by communicating with an online server. If the server is not available
     * or the operation fails, an [OnlineCommunicationException] will be thrown. Optionally, a new
     * message will be returned, which can either be given to the appropriate service on this device
     * or will be send over the ad hoc network.
     */
    @Throws(OnlineCommunicationException::class)
    fun handleMessage(message: BrocoliMessage): BrocoliMessage?

    fun willHandleMessages() = true

    @Throws(OnlineCommunicationException::class)
    fun fetchMessagesFromServer(): List<BrocoliMessage>
}

class OnlineCommunicationException : Exception {
    constructor(message: String?) : super(message)
}

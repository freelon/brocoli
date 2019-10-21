package de.upb.cs.brocoli.neighborhoodwatch

import de.upb.cs.brocoli.model.Message

/**
 * An interface for serializing and de-serializing the model messages into byte arrays, to be used
 * in the [Connectivity] interface for transmitting the messages.
 */
interface MessageSerializer {

    /**
     * Converts [message] into a [ByteArray]. Throws a [SerializationException] is the message couldn't
     * be serialized.
     */
    fun pipeMessageToBytes(message: PipeMessage): ByteArray

    /**
     * Converts [ByteArray] into a [Message]. Throws a [SerializationException] is the message couldn't
     * be deserialized.
     */
    fun bytesToPipeMessage(bytes: ByteArray): PipeMessage
}

class SerializationException(message: String?, cause: Throwable?) : RuntimeException(message, cause)

package de.upb.cs.brocoliserver.connectivity

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import de.upb.cs.brocoliserver.library.MessageSerializer
import de.upb.cs.brocoliserver.library.InterfaceAdapter
import de.upb.cs.brocoliserver.model.Message
import de.upb.cs.brocoliserver.neighborhoodwatch.PipeMessage
import de.upb.cs.brocoliserver.library.SerializationException

class JsonMessageSerializer : MessageSerializer {
    private val gson: Gson = GsonBuilder()
            .registerTypeAdapter(Message::class.java, InterfaceAdapter<Message>())
            .registerTypeAdapter(PipeMessage::class.java, InterfaceAdapter<PipeMessage>())
            .setPrettyPrinting()
            .create()

    /**
     * Converts [message] into a [ByteArray].
     */
    override fun pipeMessageToBytes(message: PipeMessage): ByteArray = gson.toJson(message, PipeMessage::class.java).toByteArray()

    /**
     * Converts [ByteArray] into a [Message]. Throws a [SerializationException] is the message couldn't
     * be deserialized.
     */
    override fun bytesToPipeMessage(bytes: ByteArray): PipeMessage {
        val stringMessage = String(bytes)
        try {
            return gson.fromJson(stringMessage, PipeMessage::class.java)
        } catch (e: Exception) {
            throw SerializationException("The bytes couldn't be deserialized into a message", e)
        }
    }

}

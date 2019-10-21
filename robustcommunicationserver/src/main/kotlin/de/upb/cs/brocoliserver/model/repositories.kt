package de.upb.cs.brocoliserver.model

import de.upb.cs.brocoliserver.model.Ack
import de.upb.cs.brocoliserver.model.AlgorithmContentMessage
import de.upb.cs.brocoliserver.model.Contact

interface ContactRepository {
    fun getAll(): List<Contact>

    fun add(contact: Contact)

    fun remove(contact: Contact)
}

interface AlgorithmMessageRepository {
    fun getAllMessages(): List<AlgorithmContentMessage>

    fun removeMessage(message: AlgorithmContentMessage)

    fun add(message: AlgorithmContentMessage)

    fun deleteByIds(messageIds: List<String>)

    fun deleteAll()
}

interface AckRepository {

    fun add(ack: Ack)

    fun remove(ack: Ack)

    fun addAll(acks: List<Ack>)

    fun getAcknowledgementsAsList(): List<Ack>

    fun deleteAll()

    fun deleteByExpiryDate(expiryDate: Long)
}

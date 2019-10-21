package de.upb.cs.brocoliserver.standalone

import de.upb.cs.brocoliserver.Log
import de.upb.cs.brocoliserver.model.*

/**
 * Implements a very simple data store in memory
 */
class InMemoryRepository : AlgorithmMessageRepository, ContactRepository, AckRepository {
    private val messages: MutableSet<AlgorithmContentMessage> = hashSetOf()
    private val contacts: MutableSet<Contact> = hashSetOf()
    private val acknowledgements: MutableSet<Ack> = hashSetOf()

    @Synchronized
    override fun getAll(): List<Contact> = contacts.toList()

    @Synchronized
    override fun add(contact: Contact) {
        contacts.add(contact)
    }

    @Synchronized
    override fun remove(contact: Contact) {
        contacts.remove(contact)
    }

    @Synchronized
    override fun getAllMessages(): List<AlgorithmContentMessage> = messages.toList()

    @Synchronized
    override fun removeMessage(message: AlgorithmContentMessage) {
        messages.remove(message)
    }

    @Synchronized
    override fun deleteByIds(messageIds: List<String>) {
        messages.removeAll({ it.id in messageIds })
    }

    @Synchronized
    override fun add(message: AlgorithmContentMessage) {
        try {
            val content = String(message.content.toByteArray())
            Log.d("InMemoryRepository", "Stored message from ${message.fromId}: $content")
        } catch (e: Exception) {}
        messages.add(message)
    }

    @Synchronized
    override fun add(ack: Ack) {
        val acks = acknowledgements.filter { it.id == ack.id }
        if (acks.isNotEmpty()) return
        acknowledgements.add(ack)
        Log.d("InMemoryRepository", "Stored ack for ${ack.id}")
    }

    @Synchronized
    override fun getAcknowledgementsAsList(): List<Ack> = acknowledgements.toList()

    @Synchronized
    override fun addAll(acks: List<Ack>) {
        acknowledgements.addAll(acks.filter { it.id !in acknowledgements.map { it.id }.toList() })
    }

    @Synchronized
    override fun remove(ack: Ack) {
        acknowledgements.remove(ack)
    }

    @Synchronized
    override fun deleteAll() {
        acknowledgements.clear()
        messages.clear()
        contacts.clear()
    }

    @Synchronized
    override fun deleteByExpiryDate(expiryDate: Long) {
        val toDelete = acknowledgements.filter { it.expiryDate <= expiryDate }
        acknowledgements.removeAll(toDelete)
    }

}
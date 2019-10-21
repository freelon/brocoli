package de.upb.cs.brocoli.model

import android.arch.lifecycle.LiveData

interface ContactRepository {
    fun getAll(): List<Contact>

    fun add(contact: Contact)

    fun remove(contact: Contact)
}

interface AlgorithmMessageRepository {
    fun getAllMessagesAsList(): List<AlgorithmContentMessage>

    fun getAllMessages() : LiveData<List<AlgorithmContentMessage>>

    fun removeMessage(message: AlgorithmContentMessage)

    fun add(message: AlgorithmContentMessage)

    fun deleteByIds(messageIds: List<String>)

    fun deleteAll()
}

interface AckRepository {

    fun add(ack: Ack)

    fun remove(ack: Ack)

    fun addAll(acks: List<Ack>)

    fun getAcknowledgements(): LiveData<List<Ack>>

    fun getAcknowledgementsAsList(): List<Ack>

    fun deleteAll()

    fun deleteByExpiryDate(expiryDate: Long)
}

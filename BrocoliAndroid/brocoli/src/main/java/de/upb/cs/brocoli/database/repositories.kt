package de.upb.cs.brocoli.database

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.Transformations
import android.arch.persistence.room.*
import com.google.gson.Gson
import de.upb.cs.brocoli.database.util.fromBase64ToByteArray
import de.upb.cs.brocoli.database.util.toBase64String
import de.upb.cs.brocoli.library.BrocoliPriority
import de.upb.cs.brocoli.model.*
import de.upb.cs.brocoli.neighborhoodwatch.MessageCreationInfo


/*
 * Here the various repository interfaces should be implemented and make use of the database to the objects.
 */

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
    override fun getAllMessagesAsList(): List<AlgorithmContentMessage> = messages.toList()

    override fun getAllMessages(): LiveData<List<AlgorithmContentMessage>> {
        throw NotImplementedError("not implemented in InMemoryRepository")
    }

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
        messages.add(message)
    }

    @Synchronized
    override fun add(ack: Ack) {
        val acks = acknowledgements.filter { it.id == ack.id }
        if (acks.isNotEmpty()) return
        acknowledgements.add(ack)
    }

    @Synchronized
    override fun getAcknowledgements(): LiveData<List<Ack>> {
        throw NotImplementedError("not implemented in InMemoryRepository")
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

class TypeConvertersForDatabase {
    companion object {
        val gson = Gson()
    }

    @TypeConverter
    fun toBrocoliPriority(value: String): BrocoliPriority = BrocoliPriority.valueOf(value)

    @TypeConverter
    fun brocoliPriorityToString(value: BrocoliPriority): String = value.name

    @TypeConverter
    fun fromMessageCreationInfo(value: MessageCreationInfo?): String = if (value == null) "" else gson.toJson(value)

    @TypeConverter
    fun toMessageCreationInfo(value: String): MessageCreationInfo? = if (value == "") null else gson.fromJson(value, MessageCreationInfo::class.java)
}

@Entity
class DbAlgorithmContentMessage(@PrimaryKey val id: String, val fromId: String, val toId: String, val serviceId: Byte, val timestamp: Long,
                                val ttlHours: Byte, val priority: BrocoliPriority, val messageBody: String)

@Dao
interface AlgorithmMessageDao {
    @Query("Select * From DbAlgorithmContentMessage")
    fun getAll(): LiveData<List<DbAlgorithmContentMessage>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun add(message: DbAlgorithmContentMessage)

    @Query("Select * From DbAlgorithmContentMessage")
    fun getAllAsList(): List<DbAlgorithmContentMessage>

    @Query("Select * From DbAlgorithmContentMessage WHERE toId = :toId ")
    fun getAllAsList(toId: String): List<DbAlgorithmContentMessage>

    @Query("Delete From DbAlgorithmContentMessage WHERE id IN (:deleteIds)")
    fun deleteByIdList(deleteIds: List<String>)

    @Query("Delete From DbAlgorithmContentMessage")
    fun deleteAll()
}

class AlgorithmMessageRepositoryImplementation(private val roomDao: AlgorithmMessageDao, private val newMessageCallbackFunction: ((message: AlgorithmContentMessage) -> Unit)?) : AlgorithmMessageRepository {

    companion object {
        private val SQLITE_MAX_VARIABLE_NUMBER = 999
    }

    override fun getAllMessages(): LiveData<List<AlgorithmContentMessage>> = Transformations.map(roomDao.getAll()) {
        it?.map { dbMessageToAlgorithmMessage(it) }
    }

    override fun getAllMessagesAsList(): List<AlgorithmContentMessage> =
            roomDao.getAllAsList().map {
                dbMessageToAlgorithmMessage(it)
            }

    override fun removeMessage(message: AlgorithmContentMessage) {
        roomDao.deleteByIdList(listOf(message.id))
    }

    override fun add(message: AlgorithmContentMessage) {
        roomDao.add(algorithmMessageToDbMessage(message))
        newMessageCallbackFunction?.invoke(message)
    }

    override fun deleteByIds(messageIds: List<String>) {
        //split the message ids into smaller lists of a maximum size of 999 items to prevent
        //an sqlite error.
        val splitMessageIds = messageIds.withIndex().groupBy { it.index / SQLITE_MAX_VARIABLE_NUMBER }
                .map { it.value.map { it.value } }
        splitMessageIds.forEach {
            roomDao.deleteByIdList(it)
        }
    }

    override fun deleteAll() {
        roomDao.deleteAll()
    }
}

@Entity
class DbAcknowledgements(@PrimaryKey val id: String, val expiryDate: Long)

@Dao
interface AcknowledgementsDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun add(dbAcknowledgement: DbAcknowledgements)

    @Delete
    fun remove(dbAcknowledgement: DbAcknowledgements)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun addAll(dbAcknowledgement: List<DbAcknowledgements>)

    @Query("SELECT * FROM DbAcknowledgements")
    fun getAcknowledgements(): LiveData<List<DbAcknowledgements>>

    @Query("Delete  FROM DbAcknowledgements")
    fun deleteAll()

    @Query("Delete from DbAcknowledgements where expiryDate<= :expiryDate")
    fun deleteByExpiryDate(expiryDate: Long)

    @Query("SELECT * FROM DbAcknowledgements")
    fun getAcknowledgementsAsList(): List<DbAcknowledgements>
}

class AcknowledgementsRepositoryImplementation(private val roomDao: AcknowledgementsDao) : AckRepository {
    override fun getAcknowledgements(): LiveData<List<Ack>> = Transformations.map(roomDao.getAcknowledgements()) { dbAcknowledgements ->
        dbAcknowledgements?.map {
            Ack(id = it.id, expiryDate = it.expiryDate)
        }?.toList() ?: listOf()
    }

    override fun add(ack: Ack) = roomDao.add(DbAcknowledgements(id = ack.id, expiryDate = ack.expiryDate))

    override fun remove(ack: Ack) = roomDao.remove(DbAcknowledgements(ack.id, ack.expiryDate))


    override fun addAll(acks: List<Ack>) = roomDao.addAll(acks.map {
        DbAcknowledgements(id = it.id, expiryDate = it.expiryDate)
    })

    override fun deleteAll() = roomDao.deleteAll()

    override fun deleteByExpiryDate(expiryDate: Long) = roomDao.deleteByExpiryDate(expiryDate)

    override fun getAcknowledgementsAsList(): List<Ack> = roomDao.getAcknowledgementsAsList().map {
        Ack(it.id, it.expiryDate)
    }

}

fun algorithmMessageToDbMessage(message: AlgorithmContentMessage): DbAlgorithmContentMessage =
        DbAlgorithmContentMessage(
                fromId = message.fromId,
                toId = message.toId,
                serviceId = message.serviceId,
                timestamp = message.timestamp,
                ttlHours = message.ttlHours,
                priority = message.priority,
                messageBody = message.content.toByteArray().toBase64String(),
                id = message.id
        )

fun dbMessageToAlgorithmMessage(message: DbAlgorithmContentMessage): AlgorithmContentMessage =
        AlgorithmContentMessage(
                fromId = message.fromId,
                toId = message.toId,
                serviceId = message.serviceId,
                timestamp = message.timestamp,
                ttlHours = message.ttlHours,
                priority = message.priority,
                content = message.messageBody.fromBase64ToByteArray().toTypedArray(),
                id = message.id
        )

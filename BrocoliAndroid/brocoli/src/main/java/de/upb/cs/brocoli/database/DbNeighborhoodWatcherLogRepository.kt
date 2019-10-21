package de.upb.cs.brocoli.database

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.Transformations
import android.arch.persistence.room.*
import de.upb.cs.brocoli.database.LogEventClassType.*
import de.upb.cs.brocoli.neighborhoodwatch.*
import de.upb.cs.brocoli.ui.NeighborhoodWatcherLogReader
import java.util.*


enum class LogEventClassType {
    Advertising, Discovery, Connectivity, Message
}

class LogDbConverters {
    @TypeConverter
    fun toLogEventClassType(string: String) = LogEventClassType.valueOf(string)

    @TypeConverter
    fun logEventClassTypeToString(classType: LogEventClassType) = classType.toString()
}

@Entity
class DbLogEvent(
        val classType: LogEventClassType,
        val eventType: String,
        val timestamp: Long,
        val endPointId: String = "",
        val additionalInfo: String = "",
        @PrimaryKey(autoGenerate = true) val id: Long = 0,
        val receiverId: String = "",
        val messageType: String = "",
        val contentMessageId: String = "",
        val creationInfo: MessageCreationInfo? = null
)

class NeighborhoodWatcherLogImplementation(private val dao: LogEventDao) : NeighborhoodWatcherLog, NeighborhoodWatcherLogReader {
    override fun addLogEntry(entry: LogEvent) {
        val dbEvent = convertLogEventToDbLogEvent(entry)
        dao.insert(dbEvent)
    }

    override fun getRecentLog(timestamp: Long): LiveData<List<LogEvent>> =
            Transformations.map(dao.getRecentLog(timestamp)) { events ->
                events?.map { convertDbLogEventToLogEvent(it) }?.toList() ?: listOf()
            }

    override fun getCompleteLog(): LiveData<List<LogEvent>> =
            Transformations.map(dao.getCompleteLog()) { events ->
                events?.map { convertDbLogEventToLogEvent(it) }?.toList() ?: listOf()
            }

    override fun getCompleteLogAsList(): List<LogEvent> = dao.getCompleteLogAsList().map { convertDbLogEventToLogEvent(it) }

    /**
     * Removes all log entries
     */
    override fun clearLog() {
        dao.deleteAll()
    }
}

private fun convertDbLogEventToLogEvent(event: DbLogEvent): LogEvent =
        when (event.classType) {
            Advertising -> AdvertisingEvent(AdvertisingEvent.Type.valueOf(event.eventType),
                    event.additionalInfo, Date(event.timestamp))
            Discovery -> DiscoveryEvent(DiscoveryEvent.Type.valueOf(event.eventType),
                    event.endPointId, event.additionalInfo, Date(event.timestamp))
            Connectivity -> ConnectionEvent(ConnectionEvent.Type.valueOf(event.eventType),
                    event.endPointId, event.additionalInfo, Date(event.timestamp))
            Message -> MessageEvent(MessageEvent.Type.valueOf(event.eventType), event.endPointId,
                    event.receiverId, event.messageType, event.contentMessageId,
                    event.additionalInfo, Date(event.timestamp), creationInfo = event.creationInfo)
        }

fun convertLogEventToDbLogEvent(entry: LogEvent): DbLogEvent =
        when (entry) {
            is AdvertisingEvent -> DbLogEvent(Advertising, entry.type.toString(), entry.timestamp.time,
                    additionalInfo = entry.additionalInfo)
            is DiscoveryEvent -> DbLogEvent(Discovery, entry.type.toString(), entry.timestamp.time,
                    entry.endPointId, entry.additionalInfo)
            is ConnectionEvent -> DbLogEvent(Connectivity, entry.type.toString(), entry.timestamp.time,
                    entry.endPointId, entry.additionalInfo)
            is MessageEvent -> DbLogEvent(Message, entry.type.toString(), entry.timestamp.time,
                    entry.sendingId, entry.additionalInfo, receiverId = entry.receivingId,
                    messageType = entry.messageType, contentMessageId = entry.contentMessageId
                    ?: "", creationInfo = entry.creationInfo)
        }

@Dao
interface LogEventDao {
    @Insert
    fun insert(event: DbLogEvent)

    @Query("SELECT * FROM DbLogEvent")
    fun getCompleteLog(): LiveData<List<DbLogEvent>>

    @Query("Select * FROM DbLogEvent WHERE timestamp > :timestamp")
    fun getRecentLog(timestamp: Long): LiveData<List<DbLogEvent>>

    @Query("SELECT * FROM DbLogEvent")
    fun getCompleteLogAsList(): List<DbLogEvent>

    @Query("Delete FROM DbLogEvent")
    fun deleteAll()
}
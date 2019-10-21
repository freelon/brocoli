package de.upb.cs.brocolitestapp

import android.arch.lifecycle.LiveData
import android.arch.persistence.room.*

@Entity
data class ChatMessage(@PrimaryKey val id: String, val from: String, val time: Long, val content: String)

@Dao
interface ChatMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun addMessage(message: ChatMessage)

    @Query("Select * from ChatMessage")
    fun getAll(): LiveData<List<ChatMessage>>

    @Query("Delete from ChatMessage")
    fun clear()

    @Query("Select count(*) from ChatMessage")
    fun count(): Int
}

@Database(entities = [ChatMessage::class], version = 2)
abstract class ChatMessageDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
}

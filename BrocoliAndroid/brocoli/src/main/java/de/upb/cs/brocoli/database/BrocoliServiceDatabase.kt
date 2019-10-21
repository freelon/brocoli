package de.upb.cs.brocoli.database

import android.arch.persistence.room.Database
import android.arch.persistence.room.RoomDatabase
import android.arch.persistence.room.TypeConverters

@Database(entities = [DbAlgorithmContentMessage::class, DbLogEvent::class, DbAcknowledgements::class, LocationUpdate::class], version = 6)
@TypeConverters(TypeConvertersForDatabase::class, LogDbConverters::class)
abstract class BrocoliServiceDatabase : RoomDatabase() {
    abstract fun brocoliMessageDao(): AlgorithmMessageDao

    abstract fun logEventDao(): LogEventDao

    abstract fun ackMessageDao(): AcknowledgementsDao

    abstract fun locationUpdateDao(): LocationUpdateDao
}


package de.upb.cs.brocoli.database

import android.arch.lifecycle.LiveData
import android.arch.persistence.room.*

@Dao
interface LocationUpdateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun addLocationUpdate(update: LocationUpdate)

    @Query("Select * from LocationUpdate ORDER BY timestamp DESC")
    fun getAll(): List<LocationUpdate>

    @Query("Select * from LocationUpdate ORDER BY timestamp DESC LIMIT 1")
    fun getLast(): LocationUpdate?

    @Query("Select * from LocationUpdate ORDER BY timestamp DESC LIMIT 1")
    fun getLastLive(): LiveData<LocationUpdate?>
}

@Entity
data class LocationUpdate(@PrimaryKey val timestamp: Long, val accuracy: Float, val latitude: Double, val longitude: Double)
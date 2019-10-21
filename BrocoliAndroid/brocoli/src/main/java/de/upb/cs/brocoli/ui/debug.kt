package de.upb.cs.brocoli.ui

import android.arch.lifecycle.LiveData
import de.upb.cs.brocoli.neighborhoodwatch.LogEvent

interface NeighborhoodWatcherLogReader {
    fun getCompleteLog(): LiveData<List<LogEvent>>

    fun getCompleteLogAsList(): List<LogEvent>
    fun getRecentLog(timestamp: Long): LiveData<List<LogEvent>>
}

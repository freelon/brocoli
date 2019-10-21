package de.upb.cs.brocoli.neighborhoodwatch

import java.util.*

interface NeighborhoodWatcher {
    /*
     * This class should implement the listeners to the nearby api, randomly connect to neighbors.
     * It uses [Connectivity] implementations to do this.
     */
    fun start()

    /**
     * Terminates all open connections, stops advertising, and removes all callbacks.
     */
    fun stop()
}

sealed class LogEvent(open val additionalInfo: String, open val timestamp: Date)

data class AdvertisingEvent(val type: Type, override val additionalInfo: String = "",
                            override val timestamp: Date = Date()) : LogEvent(additionalInfo, timestamp) {
    enum class Type {
        StartedAdvertising, StoppedAdvertising
    }
}

data class DiscoveryEvent(val type: Type, val endPointId: String, override val additionalInfo: String = "",
                          override val timestamp: Date = Date()) : LogEvent(additionalInfo, timestamp) {
    enum class Type {
        Discovered, Lost, StartedDiscoverySuccess, StartedDiscoveryFailure, StoppedDiscovery
    }
}

data class ConnectionEvent(val type: Type, val endPointId: String, override val additionalInfo: String = "",
                           override val timestamp: Date = Date()) : LogEvent(additionalInfo, timestamp) {
    enum class Type {
        RequestingConnection, ConnectionRequestedFromOther, AcceptedConnection, RejectedConnection,
        ConnectionEstablished, ConnectionError, ConnectionRejected, Disconnected
    }
}

data class MessageEvent(val type: Type, val sendingId: String, val receivingId: String, val messageType: String,
                        val contentMessageId: String? = null, override val additionalInfo: String = "",
                        override val timestamp: Date = Date(), val creationInfo: MessageCreationInfo? = null) : LogEvent(additionalInfo, timestamp) {
    enum class Type {
        MessageCreated, MessageSent, MessageReceived, MessageSendingFailed
    }
}

data class MessageCreationInfo(val serviceId: Byte, val ttlHours: Byte, val priority: String, val contentSize: Int)

interface NeighborhoodWatcherLog {
    fun addLogEntry(entry: LogEvent)

    /**
     * Removes all log entries
     */
    fun clearLog()
}
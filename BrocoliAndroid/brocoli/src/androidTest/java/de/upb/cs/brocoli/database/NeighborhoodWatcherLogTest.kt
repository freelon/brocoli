package de.upb.cs.brocoli.database

import android.arch.persistence.room.Room
import android.support.test.InstrumentationRegistry
import android.support.test.runner.AndroidJUnit4
import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.bind
import com.github.salomonbrys.kodein.instance
import com.github.salomonbrys.kodein.singleton
import de.upb.cs.brocoli.neighborhoodwatch.AdvertisingEvent
import de.upb.cs.brocoli.neighborhoodwatch.ConnectionEvent
import de.upb.cs.brocoli.neighborhoodwatch.DiscoveryEvent
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeighborhoodWatcherLogTest {
    @Test
    fun loadAndStoreTest() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getTargetContext()

        val kodein = Kodein {
            val db = Room.inMemoryDatabaseBuilder(appContext, BrocoliServiceDatabase::class.java).fallbackToDestructiveMigration().build()
            val messageRepository = NeighborhoodWatcherLogImplementation(db.logEventDao())
            bind<NeighborhoodWatcherLogImplementation>() with singleton { messageRepository }
        }

        val repo = kodein.instance<NeighborhoodWatcherLogImplementation>()

        val liveData = repo.getCompleteLog()
        liveData.observeForever {
            // empty, but someone has to observe it, so it receives updates
        }

        Thread.sleep(200)

        Assert.assertEquals("Repo should be empty initially", 0, liveData.value?.size)

        val advertiseEvent = AdvertisingEvent(AdvertisingEvent.Type.StartedAdvertising, "addInfoAdvertise")
        val discoveryEvent = DiscoveryEvent(DiscoveryEvent.Type.Discovered, "123test", "addInfoDiscovery")
        val connectionEvent = ConnectionEvent(ConnectionEvent.Type.ConnectionRejected, "345test", "additional info about connection event")

        repo.addLogEntry(advertiseEvent)
        repo.addLogEntry(discoveryEvent)
        repo.addLogEntry(connectionEvent)

        Thread.sleep(200) // necessary because otherwise this will be asserted before the update went through the notification change of LiveData

        Assert.assertNotNull("LiveData should give a non-empty list", liveData.value)
        Assert.assertEquals("advertise event loaded and restored correctly", advertiseEvent, liveData.value?.firstOrNull { it is AdvertisingEvent })
        Assert.assertEquals("discovery event loaded and restored correctly", discoveryEvent, liveData.value?.firstOrNull { it is DiscoveryEvent })
        Assert.assertEquals("connection event loaded and restored correctly", connectionEvent, liveData.value?.firstOrNull { it is ConnectionEvent })
    }
}
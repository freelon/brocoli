package de.upb.cs.brocoli.integration

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.bind
import com.github.salomonbrys.kodein.singleton
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.spy
import de.upb.cs.brocoli.connectivity.Connectivity
import de.upb.cs.brocoli.connectivity.JsonMessageSerializer
import de.upb.cs.brocoli.database.InMemoryRepository
import de.upb.cs.brocoli.library.BrocoliPriority
import de.upb.cs.brocoli.library.UserID
import de.upb.cs.brocoli.model.*
import de.upb.cs.brocoli.neighborhoodwatch.MessageSerializer
import de.upb.cs.brocoli.neighborhoodwatch.NEIGHBORHOOD_WATCHER_TIMER_INTERVAL
import de.upb.cs.brocoli.neighborhoodwatch.NeighborhoodWatcherImplementation
import de.upb.cs.brocoli.neighborhoodwatch.NeighborhoodWatcherLog
import org.junit.Assert
import org.junit.Test
import java.util.*

const val NETWORK_OPERATION_DELAY = 100L
const val DISCOVER_DELAY = 500L

class TwoDevicesIntegrationTest {
    private fun exchangeMessages(messageRouterFactory: MessageRouterFactory) {
        val deviceId1 = UserID("device1111")
        val deviceId2 = UserID("device2222")
        val serviceId = TwoDevicesIntegrationTest::class.qualifiedName!!

        val random = mock<Random>().apply {
            doReturn(0).`when`(this).nextInt(any())
        }

        val neighborhoodManager = NeighborhoodManager()

        val repo1 = InMemoryRepository().apply {
            add(AlgorithmContentMessage("1", deviceId1.id, deviceId2.id, 1, Date().time, 1, BrocoliPriority.High, "message content 1 to 2".toByteArray().toTypedArray()))
        }
        val repo2 = InMemoryRepository().apply {
            add(AlgorithmContentMessage("2", deviceId2.id, deviceId1.id, 1, Date().time, 1, BrocoliPriority.High, "message content 2 to 1".toByteArray().toTypedArray()))
        }

        val c1 = FakeConnectivity(deviceId1, neighborhoodManager, serviceId)
        val c2 = FakeConnectivity(deviceId2, neighborhoodManager, serviceId)
        val connectivity1 = spy(c1)
        val connectivity2 = spy(c2)
        println("Connectivity: $connectivity1, $c1")
        println("Connectivity: $connectivity2, $c2")


        val kodein1 = Kodein {
            bind<Connectivity>() with singleton { c1 }
            bind<NeighborhoodWatcherLog>() with singleton { ConsoleLogWriter(deviceId1) }
            bind<Long>(NEIGHBORHOOD_WATCHER_TIMER_INTERVAL) with singleton { 30000L }
            bind<MessageSerializer>() with singleton { JsonMessageSerializer() }
            bind<Random>() with singleton { random }
            bind<AlgorithmMessageRepository>() with singleton { repo1 }
            bind<AckRepository>() with singleton { repo1 }
            bind<MessageRouterFactory>() with singleton { messageRouterFactory }
        }
        val kodein2 = Kodein {
            bind<Connectivity>() with singleton { c2 }
            bind<NeighborhoodWatcherLog>() with singleton { ConsoleLogWriter(deviceId2) }
            bind<Long>(NEIGHBORHOOD_WATCHER_TIMER_INTERVAL) with singleton { 30000L }
            bind<MessageSerializer>() with singleton { JsonMessageSerializer() }
            bind<Random>() with singleton { random }
            bind<AlgorithmMessageRepository>() with singleton { repo2 }
            bind<AckRepository>() with singleton { repo2 }
            bind<MessageRouterFactory>() with singleton { messageRouterFactory }
        }
        val neighborhoodWatcher1 = NeighborhoodWatcherImplementation(kodein1, deviceId1)
        val neighborhoodWatcher2 = NeighborhoodWatcherImplementation(kodein2, deviceId2)
        neighborhoodWatcher1.start()
        Thread.sleep(250)
        neighborhoodWatcher2.start()
        Thread.sleep(2500)

        // both have to know both messages (SimpleRouter) or have acknowledgements for both (AckRouter)
        println("Repo 1: ${repo1.getAllMessagesAsList()}, ${repo1.getAcknowledgementsAsList()}")
        println("Repo 2: ${repo2.getAllMessagesAsList()}, ${repo2.getAcknowledgementsAsList()}")
        Assert.assertEquals("Repo 1 has 2 messages", 2, repo1.getAllMessagesAsList().size + repo1.getAcknowledgementsAsList().size)
        Assert.assertEquals("Repo 2 has 2 messages", 2, repo2.getAllMessagesAsList().size + repo2.getAcknowledgementsAsList().size)

        // first case: SimpleRouter, second case, AcknowledgingRouter
        Assert.assertTrue("Message from 1 to 2 was transferred", repo2.getAllMessagesAsList().any { it.id == "1" } || repo2.getAcknowledgementsAsList().any { it.id == "1" })
        Assert.assertTrue("Message from 2 to 1 was transferred", repo1.getAllMessagesAsList().any { it.id == "2" } || repo1.getAcknowledgementsAsList().any { it.id == "2" })
    }

    @Test
    fun exchangeMessagesSimpleRouter() {
        exchangeMessages(SimpleMessageRouterFactory())
    }

    @Test
    fun exchangeMessagesAcknowledgingRouter() {
        exchangeMessages(AcknowledgingMessageRouterFactory())
    }

}

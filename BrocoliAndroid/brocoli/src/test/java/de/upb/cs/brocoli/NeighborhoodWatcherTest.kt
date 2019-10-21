package de.upb.cs.brocoli

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.bind
import com.github.salomonbrys.kodein.singleton
import com.nhaarman.mockito_kotlin.*
import de.upb.cs.brocoli.connectivity.*
import de.upb.cs.brocoli.database.InMemoryRepository
import de.upb.cs.brocoli.library.UserID
import de.upb.cs.brocoli.model.AlgorithmMessageRepository
import de.upb.cs.brocoli.model.MessageRouterFactory
import de.upb.cs.brocoli.model.SimpleMessageRouterFactory
import de.upb.cs.brocoli.neighborhoodwatch.MessageSerializer
import de.upb.cs.brocoli.neighborhoodwatch.NEIGHBORHOOD_WATCHER_TIMER_INTERVAL
import de.upb.cs.brocoli.neighborhoodwatch.NeighborhoodWatcherImplementation
import de.upb.cs.brocoli.neighborhoodwatch.NeighborhoodWatcherLog
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import org.junit.Test
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class NeighborhoodWatcherTest {
    @Test
    fun discoversEndpointAndConnectsToIt() {
        val ownId = UserID("testId123")
        val serviceId = NeighborhoodWatcherTest::class.qualifiedName!!
        val neighbor = object : Neighbor("z45678") {}
        val connectivity = object : Connectivity {
            private val neighbors = mutableListOf<Neighbor>()
            private var connectionCallback: ConnectionLifecycleCallback? = null
            private var payloadCount = AtomicInteger(0)

            /**
             * The [UserID] of the device the interface is instantiated on.
             */
            override val ownId: UserID
                get() = ownId

            /**
             * Starts advertising an endpoint for a local app using the [serviceId] and [ownId].
             * @param serviceId the id of the service we are using. Should be the app namespace, or something similarly unique
             * @param connectionLifecycleCallback is the callback that will be invoked for every incoming connection
             * @param advertisingListener used to get the results of the advertising (advertise succeeded or failed)
             */
            override fun startAdvertising(connectionLifecycleCallback: ConnectionLifecycleCallback, advertisingListener: (success: Boolean, optionalMessage: String?) -> Unit) {}

            /**
             * Stops advertising.
             */
            override fun stopAdvertising() {
                TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }

            /**
             * Starts discovery for remote endpoints with the specified service ID.
             * @param serviceId the id of the service we are using
             * @param discoveryCallback is the callback that will be invoked for every neighbor that is discovered or lost.
             * @param discoveringListener used to get the results of the discovering (starting discovery succeeded or failed)
             */
            override fun startDiscovery(discoveryCallback: EndpointDiscoveryCallback, discoveringListener: (success: Boolean, optionalMessage: String?) -> Unit) {
                launch {
                    discoveringListener(true, null)
                    delay(200)
                    neighbors.add(neighbor)
                    discoveryCallback.onEndpointFound(neighbor, DiscoveredEndpointInfo(serviceId, neighbor.id))
                }
            }

            /**
             * Accepts a connection to an endpoint.
             * @param endpoint the neighbor with whom we accept to connect
             * @param callback is the callback that will be invoked for every payload that is sent/received from that neighbor
             * @param acceptListener used to get the results of accepting connection
             */
            override fun acceptConnection(endpoint: Neighbor, callback: PayloadCallback, acceptListener: (success: Boolean, optionalMessage: String?) -> Unit) {
                println("acceptConnection called")
                launch {
                    acceptListener(true, null)
                    delay(200)
                    connectionCallback!!.onConnectionResult(neighbor,
                            ConnectionResolution(ConnectionResolution.Status(ConnectionResolution.Status.ConnectionStatusCode.OK)))
                    println("connected callback")
                }
            }

            /**
             * Cancels a Payload currently in-flight to or from remote endpoint(s)
             * @param payloadId the id of the payload we want to cancel sending
             * @param cancelListener used to get the result of canceling sending the payload
             */
            override fun cancelPayload(payloadId: Long, cancelListener: (success: Boolean, optionalMessage: String?) -> Unit) {}

            /**
             * Disconnects from a remote endpoint.
             * @param endpoint the neighbor from whom we want to disconnect
             */
            override fun disconnectFromEndpoint(endpoint: Neighbor) {
                TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }

            /**
             * Rejects a connection to a remote endpoint.
             * @param endpoint the neighbor with whom we refuse to connect
             * @param rejectListener used to get the result of rejecting the connection
             */
            override fun rejectConnection(endpoint: Neighbor, rejectListener: (success: Boolean) -> Unit) {}

            /**
             * Sends a request to connect to a remote endpoint
             * @param endpoint  Neighbor with whom we want to connect
             * @param callback is the callback that notifies about the success of the connection attempt
             * @param requestListener used to get the result of requesting Connection
             */
            override fun requestConnection(endpoint: Neighbor, callback: ConnectionLifecycleCallback, requestListener: (success: Boolean, optionalMessage: String?) -> Unit) {
                connectionCallback = callback
                launch {
                    delay(20)
                    requestListener(true, null)
                    delay(100)
                    callback.onConnectionInitiated(neighbor, ConnectionInfo(neighbor.id, false))
                    println("Connection initiated callback")
                }
            }

            /**
             * Sends a Payload to a remote endpoint.
             * @param endpoint Neighbor to whom to want to send payload
             * @param payload the payload we want to sent
             * @param sendListener used to get the result of sending payload
             */
            override fun sendPayload(endpoint: Neighbor, payload: Payload, sendListener: (success: Boolean, optionalMessage: String?) -> Unit) {}

            /**
             * Variant of sendPayload(String, Payload) that takes a list of remote endpoint IDs.
             * @param endpoints List of neighbors
             * @param payload we want to sent
             * @param sendAllListener used to get result of sending payload to list of neighbors
             */
            override fun sendPayload(endpoints: List<Neighbor>, payload: Payload, sendAllListener: (success: Boolean, optionalMessage: String?) -> Unit) {
                TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }

            /**
             * Disconnects from, and removes all traces of all connected and/or discovered endpoints
             */
            override fun stopAllEndpoints() {
                TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }

            /**
             * Gets a list of currently visible neighbors. This list will not be updated.
             */
            override fun getNeighbors(): List<Neighbor> = neighbors

            /**
             * Retrieves a list of neighbors to which this device is currently connected. The list will not be updated.
             */
            override fun getConnectedNeighbors(): List<Neighbor> = listOf()

            /**
             * Stops discovering.
             */
            override fun stopDiscovery() {
            }

            /**
             * Creates a new unique payload object.
             */
            override fun createPayload(from: ByteArray): Payload = object : Payload {
                override fun getId(): Long = payloadCount.incrementAndGet().toLong()

                override fun getBytes(): ByteArray = from
            }

            /**
             * Stops all activity in the connectivity component, which should not be used after this anymore (undefined behavior)
             */
            override fun close() {
                // nothing to do here
            }


            override fun initiate() {
            }
        }
        val spyConnectivity = spy<Connectivity>(connectivity)
        val random = mock<Random>().apply {
            doReturn(0).`when`(this).nextInt(any())
        }

        val kodein = Kodein {
            bind<Connectivity>() with singleton { spyConnectivity }
            bind<NeighborhoodWatcherLog>() with singleton { mock<NeighborhoodWatcherLog>() }
            bind<Long>(NEIGHBORHOOD_WATCHER_TIMER_INTERVAL) with singleton { 3000L }
            bind<MessageSerializer>() with singleton { JsonMessageSerializer() }
            bind<Random>() with singleton { random }
            bind<AlgorithmMessageRepository>() with singleton { InMemoryRepository() }
            bind<MessageRouterFactory>() with singleton { SimpleMessageRouterFactory() }
        }

        val nw = NeighborhoodWatcherImplementation(kodein, ownId)
        nw.start()
        Thread.sleep(1000)
        verify(spyConnectivity).startDiscovery(any(), any())
        verify(spyConnectivity).requestConnection(eq(neighbor), any(), any())
        verify(spyConnectivity, times(1)).acceptConnection(eq(neighbor), any(), any())
    }
}

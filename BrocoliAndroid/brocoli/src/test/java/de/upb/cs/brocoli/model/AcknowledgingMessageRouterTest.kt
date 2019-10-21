package de.upb.cs.brocoli.model

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.bind
import com.github.salomonbrys.kodein.singleton
import com.nhaarman.mockito_kotlin.*
import de.upb.cs.brocoli.database.InMemoryRepository
import de.upb.cs.brocoli.library.BrocoliPriority
import de.upb.cs.brocoli.library.UserID
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import java.util.*


class AcknowledgingMessageRouterTest {

    private val inMemoryRepository = InMemoryRepository()
    private val kodein: Kodein = Kodein {
        bind<AlgorithmMessageRepository>() with singleton { inMemoryRepository }
        bind<AckRepository>() with singleton { inMemoryRepository }
        bind<MessageRouterFactory>() with singleton { AcknowledgingMessageRouterFactory() }
    }
    @Captor
    private val pipeCapture: ArgumentCaptor<Pipe.PipeObserver> = ArgumentCaptor.forClass(Pipe.PipeObserver::class.java)
    @Captor
    private val messageCaptor: ArgumentCaptor<Message> = ArgumentCaptor.forClass(Message::class.java)
    private val pipe: Pipe = mock()
    private val ownID: UserID = UserID("ownerUserId123456789")
    private val neighborID: UserID = UserID("targetUserId87654321")

    @Before
    fun setUp() {
        var algorithmContentMessage = AlgorithmContentMessage("1", "2", "3", 1, Date().time, 1, BrocoliPriority.High, "Message_1".toByteArray().toTypedArray())
        inMemoryRepository.add(algorithmContentMessage)
        algorithmContentMessage = AlgorithmContentMessage("2", "3", "1", 1, Date().time, 1, BrocoliPriority.High, "Message_2".toByteArray().toTypedArray())
        inMemoryRepository.add(algorithmContentMessage)
        algorithmContentMessage = AlgorithmContentMessage("3", "1", "2", 1, Date().time, 1, BrocoliPriority.High, "Message_3".toByteArray().toTypedArray())
        inMemoryRepository.add(algorithmContentMessage)
        val ack = Ack("1", 1523443623)
        inMemoryRepository.add(ack)
    }

    @Test
    fun sendListExchangeMessagesTest() {
        val messageChooser = MessageChooser(kodein, pipe, ownID, neighborID)
        messageChooser.run()
        Thread.sleep(200)
        verify(pipe, times(1)).pushMessage(argWhere<ListExchangeMessage> { it.knownMessageIds.isNotEmpty() })
        verify(pipe, times(1)).pushMessage(argWhere<ListExchangeMessage> { it.knownMessageIds.containsAll(listOf("1", "2", "3")) })
    }

    @Test
    fun listExchangeMessagesTest() {
        val messageChooser = MessageChooser(kodein, pipe, ownID, neighborID)
        messageChooser.run()
        val listExchangeMessage = ListExchangeMessage("5", "1", listOf("2"), listOf(Ack("1", Date().time + 1000000000)))
        Thread.sleep(200) //Added Delay based on website suggestion that actual invocation may be happening after the capture call and hence the failure.
        verify(pipe, times(1)).setObserver(capture(pipeCapture))
        val pipeObserver: Pipe.PipeObserver = pipeCapture.value
        pipeObserver.onMessageReceive(listExchangeMessage)
        val algorithmContentMessage = AlgorithmContentMessage("1", "2", "3", 1, Date().time, 1, BrocoliPriority.High, "Message_1".toByteArray().toTypedArray())
        Thread.sleep(100)
        pipeObserver.messageDeliveryResult(message = algorithmContentMessage, result = Pipe.DeliveryResult.Success)
        Thread.sleep(500)
        verify(mock = pipe, mode = times(2)).pushMessage(capture(messageCaptor)) //1 for send and 1 for List exchange Message , because 1 is removed by ack
        verify(mock = pipe, mode = times(1)).signalDone()
        val messageList = messageCaptor.allValues
        val listMessage: ListExchangeMessage = messageList[0] as ListExchangeMessage
        val algorithmContentMessage1: AlgorithmContentMessage = messageList[1] as AlgorithmContentMessage
        val messageID1 = algorithmContentMessage1.id
        Assert.assertTrue(listMessage.knownMessageIds.containsAll(listOf("1", "2", "3")))
        Assert.assertTrue(messageID1 in listOf("1", "3"))
    }

    @Test
    fun receiveAlgorithmContentMessageTest() {
        val messageChooser = MessageChooser(kodein, pipe, ownID, neighborID)
        messageChooser.run()
        Thread.sleep(200)
        verify(pipe).setObserver(capture(pipeCapture))
        val pipeObserver = pipeCapture.value
        val algorithmContentMessage = AlgorithmContentMessage("4", "2", "3", 1, Date().time, 1, BrocoliPriority.High, "Message_4".toByteArray().toTypedArray())
        pipeObserver.onMessageReceive(algorithmContentMessage)
        Assert.assertTrue(inMemoryRepository.getAllMessagesAsList().any { it.id == "4" })
    }
}

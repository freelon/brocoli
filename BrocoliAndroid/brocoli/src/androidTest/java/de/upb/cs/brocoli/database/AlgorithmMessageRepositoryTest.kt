package de.upb.cs.brocoli.database

import android.arch.persistence.room.Room
import android.support.test.InstrumentationRegistry
import android.support.test.runner.AndroidJUnit4
import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.bind
import com.github.salomonbrys.kodein.instance
import com.github.salomonbrys.kodein.singleton
import de.upb.cs.brocoli.library.BrocoliPriority
import de.upb.cs.brocoli.model.AlgorithmContentMessage
import de.upb.cs.brocoli.model.AlgorithmMessageRepository
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see [Testing documentation](http://d.android.com/tools/testing)
 */
@RunWith(AndroidJUnit4::class)
class AlgorithmMessageRepositoryTest {

    private lateinit var kodein: Kodein

    @Before
    fun setUp() {
        val appContext = InstrumentationRegistry.getTargetContext()
        kodein = Kodein {
            val db = Room.inMemoryDatabaseBuilder(appContext, BrocoliServiceDatabase::class.java).fallbackToDestructiveMigration().build()
            val messageRepository = AlgorithmMessageRepositoryImplementation(db.brocoliMessageDao(), null)
            bind<BrocoliServiceDatabase>() with singleton { db }
            bind<AlgorithmMessageRepository>() with singleton { messageRepository }
        }

    }

    @Test
    @Throws(Exception::class)
    fun loadAndStore() {
        val repo = kodein.instance<AlgorithmMessageRepository>()

        val inputArray = arrayOf<Byte>(0, 0, 0, 0)

        repo.add(AlgorithmContentMessage(
                fromId = "from1234567890123456",
                toId = "to123456789012345678",
                serviceId = 0,
                timestamp = 1337,
                ttlHours = 2,
                priority = BrocoliPriority.Severe,
                content = inputArray,
                id = "messageId1"
        ))

        assertArrayEquals("The message content should be fully recovered", inputArray, repo.getAllMessagesAsList().first().content)

        assertEquals("there should be exactly one message in the repo", 1, repo.getAllMessagesAsList().size)

        repo.add(AlgorithmContentMessage(
                fromId = "from1234567890123456",
                toId = "to123456789012345678",
                serviceId = 13,
                timestamp = 42,
                ttlHours = 8,
                priority = BrocoliPriority.Low,
                content = arrayOf(0, 0, 0, 0),
                id = "messageId2"
        ))
        assertEquals("Now there should be exactly two messages in the repo", 2, repo.getAllMessagesAsList().size)
    }

    @Test
    fun testDeleteByIds() {

        val repo = kodein.instance<AlgorithmMessageRepository>()

        repo.add(AlgorithmContentMessage(
                fromId = "from1234567890123456",
                toId = "to123456789012345678",
                serviceId = 0,
                timestamp = 1337,
                ttlHours = 2,
                priority = BrocoliPriority.Severe,
                content = arrayOf(0, 0, 0, 0),
                id = "messageId1"
        ))
        repo.add(AlgorithmContentMessage(
                fromId = "from1234567890123456",
                toId = "to123456789012345678",
                serviceId = 13,
                timestamp = 42,
                ttlHours = 8,
                priority = BrocoliPriority.Low,
                content = arrayOf(0, 0, 0, 0),
                id = "messageId2"
        ))
        repo.add(AlgorithmContentMessage(
                fromId = "from1234567890123456",
                toId = "to123456789012345678",
                serviceId = 13,
                timestamp = 42,
                ttlHours = 8,
                priority = BrocoliPriority.Low,
                content = arrayOf(0, 0, 0, 0),
                id = "messageId3"
        ))

        val toIds: MutableList<String> = mutableListOf()
        toIds.add("messageId1")
        toIds.add("messageId2")
        repo.deleteByIds(toIds)
        assertEquals("No message should be returned for ID ('messageId1') that is not contained", 0, repo.getAllMessagesAsList().filter { it.id == "messageId1" }.size)
        assertEquals("No message should be returned for ID ('messageId2') that is not contained", 0, repo.getAllMessagesAsList().filter { it.id == "messageId2" }.size)
        assertEquals("Exactly one message should be returned that has the queried ID", 1, repo.getAllMessagesAsList().size)
    }

    @Test
    fun testDeleteByIdsMoreThanSqliteMax() {
        val repo = kodein.instance<AlgorithmMessageRepository>()

        val deleteIds: MutableList<String> = mutableListOf()
        for (i in 1..3000) {
            val newId = "messageId$i"
            if (i <= 1500) {
                deleteIds.add(newId)
            }
            repo.add(AlgorithmContentMessage(
                    fromId = "from1234567890123456",
                    toId = "to123456789012345678",
                    serviceId = 13,
                    timestamp = 42,
                    ttlHours = 8,
                    priority = BrocoliPriority.Low,
                    content = arrayOf(0, 0, 0, 0),
                    id = newId
            ))
        }

        repo.deleteByIds(deleteIds)

        assertEquals("It should be 1500 messages left in the repo",1500,repo.getAllMessages().size)

    }
}

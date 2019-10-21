package de.upb.cs.brocoli.database

import android.arch.persistence.room.Room
import android.support.test.InstrumentationRegistry
import android.support.test.runner.AndroidJUnit4
import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.bind
import com.github.salomonbrys.kodein.instance
import com.github.salomonbrys.kodein.singleton
import de.upb.cs.brocoli.model.Ack
import de.upb.cs.brocoli.model.AckRepository
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AcknowledgementMessageRepositoryTest {
    private lateinit var kodein: Kodein

    @Before
    fun setUp() {
        val appContext = InstrumentationRegistry.getTargetContext()
        kodein = Kodein {
            val db = Room.inMemoryDatabaseBuilder(appContext, BrocoliServiceDatabase::class.java).allowMainThreadQueries().build()
            val messageRepository = AcknowledgementsRepositoryImplementation(db.ackMessageDao())
            bind<BrocoliServiceDatabase>() with singleton { db }
            bind<AckRepository>() with singleton { messageRepository }
        }
    }

    @Test
    fun testAdd() {
        val repo = kodein.instance<AckRepository>()
        repo.add(Ack("1", 1234))
        val liveData = repo.getAcknowledgements()
        liveData.observeForever {
            // empty, but someone has to observe it, so it receives updates
        }
        Thread.sleep(200)
        Assert.assertNotNull("LiveData should give a non-empty list", liveData.value)
        Assert.assertEquals("Item value is 1", "1", liveData.value?.get(0)?.id)
        Assert.assertEquals("there exists one item", 1, liveData.value?.size)
        repo.add(Ack("2", 2345))
        Thread.sleep(200)
        Assert.assertNotNull("LiveData should give a non-empty list", liveData.value)
        Assert.assertEquals("Item value is 1", "1", liveData.value?.get(0)?.id)
        Assert.assertEquals("Item value is 2", "2", liveData.value?.get(1)?.id)
        Assert.assertEquals("there exists two items", 2, liveData.value?.size)
  }

    @Test
    fun testAddAll() {
        val repo = kodein.instance<AckRepository>()
        val ackList: MutableList<Ack> = arrayListOf()
        ackList.add(Ack("4", 1234))
        ackList.add(Ack("5", 1234))
        repo.addAll(ackList)
        val liveData = repo.getAcknowledgements()
        liveData.observeForever {
            // empty, but someone has to observe it, so it receives updates
        }
        Thread.sleep(200)
        Assert.assertNotNull("LiveData should give a non-empty list", liveData.value)
        Assert.assertEquals("Item value is 4", "4", liveData.value?.get(0)?.id)
        Assert.assertEquals("Item value is 5", "5", liveData.value?.get(1)?.id)
        Assert.assertEquals("there exists two items", 2, liveData.value?.size)
     }

    @Test
    fun testDelete() {
        val repo = kodein.instance<AckRepository>()
        val ackList: MutableList<Ack> = arrayListOf()
        ackList.add(Ack("6", 1234))
        ackList.add(Ack("7", 1234))
        ackList.add(Ack("8", 1234))
        ackList.add(Ack("9", 1234))
        repo.addAll(ackList)
        val liveData = repo.getAcknowledgements()
        liveData.observeForever {
            // empty, but someone has to observe it, so it receives updates
        }
        repo.remove(Ack("7", 1234))
        Thread.sleep(200)
        Assert.assertNotNull("LiveData should give a non-empty list", liveData.value)
        Assert.assertEquals("Item value is 6", "6", liveData.value?.get(0)?.id)
        Assert.assertEquals("Item value is 8", "8", liveData.value?.get(1)?.id)
        Assert.assertEquals("Item value is 9", "9", liveData.value?.get(2)?.id)
        Assert.assertEquals("there exists three items", 3, liveData.value?.size)
        repo.remove(Ack("9", 1234))
        Thread.sleep(200)
        Assert.assertNotNull("LiveData should give a non-empty list", liveData.value)
        Assert.assertEquals("Item value is 6", "6", liveData.value?.get(0)?.id)
        Assert.assertEquals("Item value is 8", "8", liveData.value?.get(1)?.id)
        Assert.assertEquals("there exists two items", 2, liveData.value?.size)
    }

    @Test
    fun testDeleteAll() {
        val repo = kodein.instance<AckRepository>()
        val ackList: MutableList<Ack> = arrayListOf()
        ackList.add(Ack("6", 1234))
        ackList.add(Ack("7", 1234))
        ackList.add(Ack("8", 1234))
        ackList.add(Ack("9", 1234))
        repo.addAll(ackList)
        val liveData = repo.getAcknowledgements()
        liveData.observeForever {
            // empty, but someone has to observe it, so it receives updates
        }
        Thread.sleep(200)
        Assert.assertNotNull("LiveData should give a non-empty list", liveData.value)
        Assert.assertEquals("there exists four items", 4, liveData.value?.size)
        repo.deleteAll()
        Thread.sleep(200)
        Assert.assertEquals("there exists no item", 0, liveData.value?.size)
    }

    @Test
    fun testDeleteByExpiryDate() {
        val repo = kodein.instance<AckRepository>()
        val ackList: MutableList<Ack> = arrayListOf()
        ackList.add(Ack("6", 1234))
        ackList.add(Ack("7", 23456))
        ackList.add(Ack("8", 3257889))
        ackList.add(Ack("9", 555555555))
        repo.addAll(ackList)
        val liveData = repo.getAcknowledgements()
        liveData.observeForever {
            // empty, but someone has to observe it, so it receives updates
        }
        Thread.sleep(200)
        Assert.assertNotNull("LiveData should give a non-empty list", liveData.value)
        Assert.assertEquals("there exists four items", 4, liveData.value?.size)
        repo.deleteByExpiryDate(3257889)
        Thread.sleep(200)
        Assert.assertNotNull("LiveData should give a non-empty list", liveData.value)
        Assert.assertEquals("Item value is 9", "9", liveData.value?.get(0)?.id)
        Assert.assertEquals("there exists one item", 1, liveData.value?.size)
    }
}
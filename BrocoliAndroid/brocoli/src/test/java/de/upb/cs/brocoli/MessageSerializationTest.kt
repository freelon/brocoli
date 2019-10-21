package de.upb.cs.brocoli

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import de.upb.cs.brocoli.connectivity.JsonMessageSerializer
import de.upb.cs.brocoli.library.BrocoliPriority
import de.upb.cs.brocoli.model.AlgorithmContentMessage
import de.upb.cs.brocoli.model.ListExchangeMessage
import de.upb.cs.brocoli.model.Message
import de.upb.cs.brocoli.neighborhoodwatch.*
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.Deflater
import java.util.zip.Inflater

const val BUFFER_SIZE = 100

class MessageSerializationTest {
    @Test
    @Throws(Exception::class)
    fun twoWayAlgorithmContentMessageSerialization() {
        val message = AlgorithmContentMessage("id123", "fromId123", "toId456", 1, 340472L, 1, BrocoliPriority.High, "message content".toByteArray().toTypedArray())
        val pipeMessage = PipeContentMessage(message)
        val messageSerializer: MessageSerializer = JsonMessageSerializer()

        val bytes = messageSerializer.pipeMessageToBytes(pipeMessage)

        val deserializedMessage = messageSerializer.bytesToPipeMessage(bytes)

        assertTrue("the message is a PipeContentMessage", deserializedMessage is PipeContentMessage)

        val deserializedContent = (deserializedMessage as? PipeContentMessage)?.message

        assertEquals("Deserialized message should be equal to original", message, deserializedContent)
    }

    @Test
    @Throws(Exception::class)
    fun longMessageSerialization() {
        val message = AlgorithmContentMessage("9bnER_MYFZr-TQ4msQmdD5n9QSebH0f6tAU-OwTV-Vg=",
                "49d604045e1f45fcb3e58e1c49661307",
                "c40a0695e1ed4926999c813b14ee47d2",
                42,
                1575977862000,
                1,
                BrocoliPriority.High,
                "eyJyZXNwb25zZSI6IntcImNyZWF0ZWRcIjpudWxsLFwiZGVzY3JpcHRpb25cIjpcIkF1ZmdydW5kIGRlcyBzdGFya2VuIFNjaG5lZWZhbGxzIHNpbmQgdmllbGUgU3RyYcOfZW4gdW5iZWZhaHJiYXIgdW5kIG3DvHNzZW4gdm9tIFNjaG5lZSBiZWZyZWl0IHdlcmRlbi5cIixcImVuZERhdGVcIjpcIjIwMjAtMTEtMTEgMTQ6Mjg6MDFcIixcImdyb3VwSWRcIjpcImVtZXJnZW5jeVwiLFwiaWRcIjo2LFwibGFtcG9ydF90aW1lc3RhbXBcIjoxLFwibGF0aXR1ZGVcIjo1MS4xMzMwODA5LFwibG9jYXRpb25cIjpcIkdyb8OfcmF1bSBGcml0emxhclwiLFwibG9uZ2l0dWRlXCI6OS4yNzQyNjQxLFwibWVldGluZ1BsYWNlXCI6XCJJbm5lbmhvZiBkZXIgRnJlaXdpbGxpZ2VuIEZldWVyd2VociBIZWxsZW53ZWcgMTgsIDM0NTYwIEZyaXR6bGFyXCIsXCJzZWxlY3RlZFVuYm91bmRIZWxwZXJzXCI6W3tcImFjY291bnRJZFwiOlwiNWIwZDUwOTNkMWViZF8wMVwiLFwiY3JlYXRlZFwiOlwiMjAxOC0wNS0yOSAxNzo0Nzo1M1wiLFwiaWRcIjo2LFwibGFtcG9ydF90aW1lc3RhbXBcIjoxLFwicmVzb3VyY2VNYXBcIjpbe1wiY3JlYXRlZFwiOlwiMjAxOC0wNS0yOSAxNzo0Nzo1M1wiLFwiaWRcIjoyNixcImxhbXBvcnRfdGltZXN0YW1wXCI6MSxcInJpZFwiOlwiYWFhYWFhYWFhYWJiYjFcIixcInR5cGVJZFwiOlwiMDIwMjAxXCIsXCJ1SWRcIjpcIjI5YjM0ZjAyLTIxMmItNDM2Ny05YjRkLWZkZTI3OTZlMDEzY1wiLFwidXBkYXRlZFwiOlwiMjAxOC0wNS0yOSAxNzo0Nzo1M1wifSx7XCJjcmVhdGVkXCI6XCIyMDE4LTA1LTI5IDE3OjQ3OjUzXCIsXCJpZFwiOjI3LFwibGFtcG9ydF90aW1lc3RhbXBcIjoxLFwicmlkXCI6XCJhYWFhYWFhYWFhYmJiMlwiLFwidHlwZUlkXCI6XCIwMjAyMDJcIixcInVJZFwiOlwiMWNlNDhhYjItZmJhNi00MzRkLThmMzYtYTdjYzBjM2E2MWRjXCIsXCJ1cGRhdGVkXCI6XCIyMDE4LTA1LTI5IDE3OjQ3OjUzXCJ9LHtcImNyZWF0ZWRcIjpcIjIwMTgtMDUtMjkgMTc6NDc6NTNcIixcImlkXCI6MjgsXCJsYW1wb3J0X3RpbWVzdGFtcFwiOjEsXCJyaWRcIjpcImFhYWFhYWFhYWFiYmIzXCIsXCJ0eXBlSWRcIjpcIjAyMDIwM1wiLFwidUlkXCI6XCI3OGRiMzY3Yy01NTZiLTRjNmItOGMwMi1kMWI3NmFlZDY4ZDFcIixcInVwZGF0ZWRcIjpcIjIwMTgtMDUtMjkgMTc6NDc6NTNcIn0se1wiY3JlYXRlZFwiOlwiMjAxOC0wNS0yOSAxNzo0Nzo1M1wiLFwiaWRcIjoyOSxcImxhbXBvcnRfdGltZXN0YW1wXCI6MSxcInJpZFwiOlwiYWFhYWFhYWFhYWJiYjRcIixcInR5cGVJZFwiOlwiMDMwM1wiLFwidUlkXCI6XCIwYTIxYmQ0Yi0wYzJlLTQyMTEtOTA0YS1kN2Y3MWQ2MWE2MzVcIixcInVwZGF0ZWRcIjpcIjIwMTgtMDUtMjkgMTc6NDc6NTNcIn0se1wiY3JlYXRlZFwiOlwiMjAxOC0wNS0yOSAxNzo0Nzo1M1wiLFwiaWRcIjozMCxcImxhbXBvcnRfdGltZXN0YW1wXCI6MSxcInJpZFwiOlwiYWFhYWFhYWFhYWJiYjVcIixcInR5cGVJZFwiOlwiMDQwMTAzXCIsXCJ1SWRcIjpcIjE4OWIxMGMzLTkyMTUtNDY3MS1hMDU1LWYwMzRkMzdiNGQ0Y1wiLFwidXBkYXRlZFwiOlwiMjAxOC0wNS0yOSAxNzo0Nzo1M1wifV0sXCJyZXNvdXJjZXNcIjpbe1wiY2xhc3NfaWRcIjpcIlwiLFwiY291bnRhYmxlXCI6ZmFsc2UsXCJjcmVhdGVkXCI6XCIyMDE4LTA1LTI5IDE1OjQ1OjQxXCIsXCJkZWxldGVkXCI6bnVsbCxcImlkXCI6NzYsXCJsYW1wb3J0X3RpbWVzdGFtcFwiOjcsXCJxdWFsaWZpY2F0aW9uX3ZlcmlmaWNhdGlvblwiOmZhbHNlLFwicmlkXCI6bnVsbCxcInNlbGVjdGFibGVcIjpmYWxzZSxcInNpbmdsZV90aXRsZVwiOm51bGwsXCJzb3J0X251bWJlclwiOjEsXCJ0ZXh0XCI6XCJcIixcInRpdGxlXCI6XCJTY2hhdWZlbCwgU3BpdHpoYWNrZSB1bmQgw6RobmxpY2hlc1wiLFwidHlwZV9pZFwiOlwiMDIwMjAxXCIsXCJ1SWRcIjpcIjQ2NmU0MTZhLTg4YzUtNDBlZi1iM2IxLWY3YjM2YmJiMDE3NVwiLFwidXBkYXRlZFwiOlwiMjAxOC0wNS0yOSAxNzo0Nzo1M1wifSx7XCJjbGFzc19pZFwiOlwiXCIsXCJjb3VudGFibGVcIjpmYWxzZSxcImNyZWF0ZWRcIjpcIjIwMTgtMDUtMjkgMTU6NDU6NDFcIixcImRlbGV0ZWRcIjpudWxsLFwiaWRcIjo3NyxcImxhbXBvcnRfdGltZXN0YW1wXCI6NyxcInF1YWxpZmljYXRpb25fdmVyaWZpY2F0aW9uXCI6ZmFsc2UsXCJyaWRcIjpudWxsLFwic2VsZWN0YWJsZVwiOmZhbHNlLFwic2luZ2xlX3RpdGxlXCI6bnVsbCxcInNvcnRfbnVtYmVyXCI6MixcInRleHRcIjpcIlwiLFwidGl0bGVcIjpcIlNjaHVia2FycmVcIixcInR5cGVfaWRcIjpcIjAyMDIwMlwiLFwidUlkXCI6XCJmM2FmMjcyMi1hODNjLTQ4NTEtYmQyNi0xZmIxZTNjYWFkNzVcIixcInVwZGF0ZWRcIjpcIjIwMTgtMDUtMjkgMTc6NDc6NTNcIn0se1wiY2xhc3NfaWRcIjpcIlwiLFwiY291bnRhYmxlXCI6ZmFsc2UsXCJjcmVhdGVkXCI6XCIyMDE4LTA1LTI5IDE1OjQ1OjQxXCIsXCJkZWxldGVkXCI6bnVsbCxcImlkXCI6ODQsXCJsYW1wb3J0X3RpbWVzdGFtcFwiOjcsXCJxdWFsaWZpY2F0aW9uX3ZlcmlmaWNhdGlvblwiOnRydWUsXCJyaWRcIjpudWxsLFwic2VsZWN0YWJsZVwiOmZhbHNlLFwic2luZ2xlX3RpdGxlXCI6bnVsbCxcInNvcnRfbnVtYmVyXCI6MyxcInRleHRcIjpcIlwiLFwidGl0bGVcIjpcIktldHRlbnPDpGdlXCIsXCJ0eXBlX2lkXCI6XCIwMjAyMDNcIixcInVJZFwiOlwiMjQ5ZTAwNzMtZjYyMC00MmE3LWJjN2EtOTNlNGRmZGYzYjJhXCIsXCJ1cGRhdGVkXCI6XCIyMDE4LTA1LTI5IDE3OjQ3OjUzXCJ9LHtcImNsYXNzX2lkXCI6XCJcIixcImNvdW50YWJsZVwiOmZhbHNlLFwiY3JlYXRlZFwiOlwiMjAxOC0wNS0yOSAxNTo0NTo0MVwiLFwiZGVsZXRlZFwiOm51bGwsXCJpZFwiOjE2LFwibGFtcG9ydF90aW1lc3RhbXBcIjo3LFwicXVhbGlmaWNhdGlvbl92ZXJpZmljYXRpb25cIjp0cnVlLFwicmlkXCI6bnVsbCxcInNlbGVjdGFibGVcIjpmYWxzZSxcInNpbmdsZV90aXRsZVwiOm51bGwsXCJzb3J0X251bWJlclwiOjMsXCJ0ZXh0XCI6XCJcIixcInRpdGxlXCI6XCJGYWhyemV1Z2UgZsO8ciBFcmQtL0F1ZnLDpHVtYXJiZWl0ZW5cIixcInR5cGVfaWRcIjpcIjAzMDNcIixcInVJZFwiOlwiYjgxMDYwMGMtZDQ4My00YTRhLWFjNDQtMGJiZGM5MGM0N2U0XCIsXCJ1cGRhdGVkXCI6XCIyMDE4LTA1LTI5IDE3OjQ3OjUzXCJ9LHtcImNsYXNzX2lkXCI6XCJcIixcImNvdW50YWJsZVwiOmZhbHNlLFwiY3JlYXRlZFwiOlwiMjAxOC0wNS0yOSAxNTo0NTo0MVwiLFwiZGVsZXRlZFwiOm51bGwsXCJpZFwiOjYyLFwibGFtcG9ydF90aW1lc3RhbXBcIjo3LFwicXVhbGlmaWNhdGlvbl92ZXJpZmljYXRpb25cIjpmYWxzZSxcInJpZFwiOm51bGwsXCJzZWxlY3RhYmxlXCI6ZmFsc2UsXCJzaW5nbGVfdGl0bGVcIjpudWxsLFwic29ydF9udW1iZXJcIjozLFwidGV4dFwiOlwiQnNwdy4gQXVmcsOkdW1hcmJlaXRlblwiLFwidGl0bGVcIjpcIktyYWZ0aW50ZW5zaXZlIGvDtnJwZXJsaWNoZSBUw6R0aWdrZWl0ZW5cIixcInR5cGVfaWRcIjpcIjA0MDEwM1wiLFwidUlkXCI6XCJmNDgzOGRjYi00M2FhLTRmYjktOTM1MS0xMDI0ZWIwNzc2MjFcIixcInVwZGF0ZWRcIjpcIjIwMTgtMDUtMjkgMTc6NDc6NTNcIn1dLFwidUlkXCI6XCIyY2NjZjNhNy1kMDQxLTRmNDgtYjU3OS04MmM4MWI2ZjA0MzBcIixcInVwZGF0ZWRcIjpcIjIwMTgtMDUtMjkgMTc6NDc6NTNcIn1dLFwic3RhcnREYXRlXCI6XCIyMDE4LTAzLTEwIDEzOjA3OjQyXCIsXCJzdWJqZWN0XCI6XCJTdHJhw59lbiBmcmVpcsOkdW1lblwiLFwidGFza0lkXCI6XCI1YWM1Y2JmYjI1ODAzXzk1XCIsXCJ0aW1lb3V0XCI6XCIyMDE5LTEyLTEwIDEzOjM3OjQyXCIsXCJ0eXBlXCI6MSxcInVJZFwiOlwiZDllYzU1ZTEtYzJhNC00YjA3LThkMTQtYzBkODFmODFjMDUxXCIsXCJ1cGRhdGVkXCI6bnVsbCxcInZhbGlkVW50aWxcIjpcIjIwMTktMTItMTEgMTQ6Mjg6NDJcIn0iLCJ0eXBlIjoiR0VUX1JDX1RBU0tTIn0".toByteArray().toTypedArray())
        val pipeMessage = PipeContentMessage(message)
        val messageSerializer: MessageSerializer = JsonMessageSerializer()

        val bytes = messageSerializer.pipeMessageToBytes(pipeMessage)
        System.out.println("Message size in bytes: ${bytes.size}")

        val deserializedMessage = messageSerializer.bytesToPipeMessage(bytes)

        assertTrue("the message is a PipeContentMessage", deserializedMessage is PipeContentMessage)

        val deserializedContent = (deserializedMessage as? PipeContentMessage)?.message

        assertEquals("Deserialized message should be equal to original", message, deserializedContent)

    }

    @Test
    fun pipeContentMessageSerialization() {
        val contentMessage = AlgorithmContentMessage("id123", "fromId123", "toId456", 1, 340472L, 1, BrocoliPriority.High, "message content".toByteArray().toTypedArray())
        val listExchangeMessage = ListExchangeMessage("testId123", "n45678", listOf("1", "234", "2"))
        val pipeMessageContent = PipeContentMessage(contentMessage)
        val pipeMessageListExchange = PipeContentMessage(listExchangeMessage)
        val gson: Gson = GsonBuilder()
                .registerTypeAdapter(Message::class.java, InterfaceAdapter<Message>())
                .registerTypeAdapter(PipeMessage::class.java, InterfaceAdapter<PipeMessage>())
                .setPrettyPrinting()
                .create()

        val jsonContent = gson.toJson(pipeMessageContent, PipeMessage::class.java)
        val jsonListExchange = gson.toJson(pipeMessageListExchange, PipeMessage::class.java)

        val resultContent: PipeContentMessage = gson.fromJson(jsonContent, PipeMessage::class.java) as PipeContentMessage
        val resultListExchange: PipeContentMessage = gson.fromJson(jsonListExchange, PipeMessage::class.java) as PipeContentMessage

        assertEquals("Deserialized message should be equal to original", pipeMessageContent, resultContent)
        assertEquals("content should be the same", contentMessage, resultContent.message)

        assertEquals("Deserialized message should be equal to original", pipeMessageListExchange, resultListExchange)
        assertEquals("content should be the same", listExchangeMessage, resultListExchange.message)
    }

    @Test
    fun pipeProtocolMessageSerialization() {
        val pipeMessage = PipeSignalDoneMessage()
        val gson: Gson = GsonBuilder()
                .registerTypeAdapter(PipeMessage::class.java, InterfaceAdapter<PipeMessage>())
                .setPrettyPrinting()
                .create()

        val json = gson.toJson(pipeMessage, PipeMessage::class.java)

        val result = gson.fromJson(json, PipeMessage::class.java)

        assertEquals("Deserialized message should be equal to original", pipeMessage, result)
    }

    @Test
    fun compressionTest() {
        val content = "Lorem ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod tempor invidunt ut labore et dolore magna aliquyam erat, sed diam voluptua. At vero eos et accusam et justo duo dolores et ea rebum. Stet clita kasd gubergren, no sea takimata sanctus est Lorem ipsum dolor sit amet."
        val message = AlgorithmContentMessage("id123", "fromId123", "toId456", 1, 340472L, 1, BrocoliPriority.High, content.toByteArray().toTypedArray())
        val pipeMessage = PipeContentMessage(message)
        val messageSerializer: MessageSerializer = JsonMessageSerializer()

        val bytes = messageSerializer.pipeMessageToBytes(pipeMessage)

        val compressedBytes = Deflater(1).run {
            setInput(bytes)
            finish()
            val output = ByteArray(bytes.size)
            val size = deflate(output)
            end()
            output.sliceArray(0 until size)
        }

        println("Message size before/after compression: ${bytes.size}/${compressedBytes.size} bytes (reduced by " +
                "${String.format("%.1f", 100 * (bytes.size.toDouble() - compressedBytes.size.toDouble()) / bytes.size.toDouble())}%)")
        println("(the content size is ${content.length} bytes)")

        val decompressedBytes = Inflater().run {
            setInput(compressedBytes)
            var output: ByteArray
            var size: Int
            val outputList = mutableListOf<ByteArray>()

            do {
                output = ByteArray(BUFFER_SIZE)
                size = inflate(output)
                if (size > 0) {
                    outputList.add(output.sliceArray(0 until size))
                }
            } while (size == 100)
            end()
            val result = ByteArray(outputList.map { it.size }.sum())
            outputList.forEachIndexed { index, bytes ->
                System.arraycopy(bytes, 0, result, index * BUFFER_SIZE, bytes.size)
            }
            result
        }

        assertEquals("original and decompressed have same size", bytes.size, decompressedBytes.size)
        assertArrayEquals("original and decompressed are equal", bytes, decompressedBytes)

        val deserializedMessage = messageSerializer.bytesToPipeMessage(decompressedBytes)

        assertTrue("the message is a PipeContentMessage", deserializedMessage is PipeContentMessage)

        val deserializedContent = (deserializedMessage as? PipeContentMessage)?.message

        assertEquals("Deserialized message should be equal to original", message, deserializedContent)
    }
}

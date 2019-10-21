package de.upb.cs.brocoliserver.connectivity

import com.google.common.primitives.Ints
import de.upb.cs.brocoliserver.Log
import java.io.*

class OutputStreamWrapper(val os: OutputStream) : Closeable, Flushable {
    companion object {
        val TAG: String = OutputStreamWrapper::class.java.simpleName
    }

    @Throws(IOException::class)
    override fun close() {
        os.close()
    }

    @Throws(IOException::class)
    override fun flush() {
        os.flush()
    }

    @Throws(IOException::class)
    fun write(content: ByteArray) {
        check(content.isNotEmpty()) { "cannot send empty content" }
        Log.d(TAG, "Writing ${content.size} bytes")
        os.write(Ints.toByteArray(content.size))
        os.write(content)
        os.flush()
    }
}

class InputStreamWrapper(val stream: InputStream) : Closeable {
    companion object {
        val TAG: String = InputStreamWrapper::class.java.simpleName
    }

    override fun close() {
        stream.close()
    }

    /**
     * Reads the next available message, as sent in [OutputStreamWrapper.write].
     * If there is no more to read an [EOFException] is thrown.
     * [IOException]s of the underlying [InputStream] are rethrown.
     */
    @Throws(EOFException::class, IOException::class)
    fun read(): ByteArray {
        var length = Ints.BYTES
        val header = ByteArray(length)
        var read = 0
        var newRead: Int
        while (read < length) {
            newRead = stream.read(header, read, length - read)
            if (newRead < 0) {
                throw EOFException()
            }

            read += newRead
        }

        if (read != 4)
            throw IOException("Cannot read from $stream.")

        length = Ints.fromByteArray(header)
        Log.d(TAG, "reading $length bytes")
        if (length < 0) {
            throw EOFException()
        }

        val message = ByteArray(length)
        read = 0
        while (read < length) {
            newRead = stream.read(message, read, length - read)
            if (newRead < 0) {
                throw EOFException()
            }

            read += newRead
        }
        Log.d(TAG, "read ${message.size} bytes")
        return message
    }
}

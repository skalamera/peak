package com.cl600.obd.obd

import kotlinx.coroutines.delay
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared command/response reader over raw InputStream/OutputStream (Bluetooth RFCOMM or Wi-Fi TCP).
 * Uses a dedicated daemon reader thread so blocking socket reads never hang coroutines past timeout.
 */
class StreamCommandChannel(
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    private val onCloseResources: () -> Unit
) {
    private val lock = Any()
    private val buffer = StringBuilder()
    private val closed = AtomicBoolean(false)
    @Volatile
    private var readerFailure: IOException? = null

    private val readerThread = Thread({
        val temp = ByteArray(1024)
        try {
            while (!closed.get()) {
                val read = inputStream.read(temp)
                if (read < 0) {
                    readerFailure = IOException("Stream closed by remote adapter (EOF)")
                    break
                }
                if (read > 0) {
                    val chunk = String(temp, 0, read, Charsets.US_ASCII)
                    synchronized(lock) {
                        buffer.append(chunk)
                    }
                }
            }
        } catch (e: IOException) {
            if (!closed.get()) {
                readerFailure = e
            }
        }
    }, "obd-stream-reader").apply {
        isDaemon = true
        start()
    }

    val isOpen: Boolean
        get() = !closed.get() && readerFailure == null

    suspend fun sendCommand(command: String, timeoutMs: Long): String {
        if (closed.get()) throw IOException("Transport is closed")
        readerFailure?.let { throw it }

        synchronized(lock) {
            buffer.setLength(0)
        }

        val wire = (command.trim() + "\r").toByteArray(Charsets.US_ASCII)
        try {
            outputStream.write(wire)
            outputStream.flush()
        } catch (e: IOException) {
            close()
            throw e
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (closed.get()) throw IOException("Transport closed while waiting for response")
            readerFailure?.let { throw it }

            synchronized(lock) {
                val promptIndex = buffer.indexOf(">")
                if (promptIndex >= 0) {
                    val response = buffer.substring(0, promptIndex)
                    buffer.delete(0, promptIndex + 1)
                    return response
                }
            }
            delay(10L)
        }

        val partial = synchronized(lock) {
            val text = buffer.toString()
            buffer.setLength(0)
            text
        }
        if (partial.isNotBlank()) {
            return partial
        }
        throw IOException("Timed out waiting for ELM327 prompt '>' on command: $command")
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                onCloseResources()
            } catch (_: Exception) {
            }
            readerThread.interrupt()
        }
    }
}

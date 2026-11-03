package com.fob.lanparty.net

import org.json.JSONObject
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

internal class SocketLink(
    private val connection: Closeable,
    input: InputStream,
    output: OutputStream,
) {
    private val reader = input.bufferedReader(Charsets.UTF_8)
    private val writer = output.bufferedWriter(Charsets.UTF_8)
    private val writeLock = Any()

    @Volatile
    private var closed = false

    fun send(payload: JSONObject) {
        synchronized(writeLock) {
            check(!closed) { "Connection is closed" }
            writer.write(payload.toString())
            writer.newLine()
            writer.flush()
        }
    }

    fun readLine(): String? {
        val line = reader.readLine() ?: return null
        if (line.length > LanProtocol.MAX_LINE_LENGTH) {
            throw IllegalArgumentException("Protocol line is too long")
        }
        return line
    }

    fun close() {
        synchronized(writeLock) {
            if (closed) return
            closed = true
            runCatching { connection.close() }
        }
    }
}

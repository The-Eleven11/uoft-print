package dev.uoftprint.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.random.Random

class LpdClientTest {

    private class Received(var queue: String = "", var control: String = "", var controlName: String = "", var dataName: String = "", var data: ByteArray = ByteArray(0))

    /** A strict RFC 1179 receiver: checks every framing byte and replies like a real lpd. */
    private fun server(rejectQueue: Boolean = false, block: (Int, Received) -> Unit) {
        val ss = ServerSocket(0)
        val got = Received()
        var failure: Throwable? = null
        val t = thread {
            try {
                ss.accept().use { s ->
                    val inp = DataInputStream(s.getInputStream())
                    val out = s.getOutputStream()
                    fun line(): String {
                        val b = ByteArrayOutputStream()
                        while (true) { val c = inp.read(); require(c >= 0); if (c == '\n'.code) break; b.write(c) }
                        return b.toString("US-ASCII")
                    }
                    val first = line()
                    require(first[0] == '\u0002') { "expected receive-job" }
                    got.queue = first.substring(1)
                    if (rejectQueue) { out.write(1); out.flush(); return@use }
                    out.write(0)
                    repeat(2) {
                        val sub = line()
                        val (count, name) = sub.substring(1).split(' ')
                        val bytes = ByteArray(count.toInt())
                        out.write(0)
                        inp.readFully(bytes)
                        require(inp.read() == 0) { "missing terminating zero" }
                        out.write(0)
                        when (sub[0]) {
                            '\u0002' -> { got.control = String(bytes, Charsets.US_ASCII); got.controlName = name; require(got.data.isEmpty()) { "control must come first" } }
                            '\u0003' -> { got.data = bytes; got.dataName = name }
                            else -> error("bad subcommand")
                        }
                    }
                    out.flush()
                }
            } catch (e: Throwable) { failure = e } finally { ss.close() }
        }
        block(ss.localPort, got)
        t.join(10_000)
        failure?.let { throw it }
    }

    @Test
    fun sendsControlThenDataLikeCups() {
        val data = Random(3).nextBytes(300_000)
        val file = File.createTempFile("job", ".ps").apply { writeBytes(data); deleteOnExit() }
        server { port, got ->
            LpdClient("127.0.0.1", port).print("GE-BW", "Jane Doe", "Lecture 5 (slides).pdf", file, clientHost = "pixel")
            assertEquals("GE-BW", got.queue)
            assertArrayEquals(data, got.data)
            val lines = got.control.trimEnd('\n').split('\n')
            assertEquals('H', lines[0][0]); assertEquals("Hpixel", lines[0])
            assertEquals("PJane_Doe", lines[1])
            assertEquals("JLecture 5 (slides).pdf", lines[2])
            assertEquals("l${got.dataName}", lines[3])
            assertEquals("U${got.dataName}", lines[4])
            assertEquals("NLecture 5 (slides).pdf", lines[5])
            assertTrue(got.dataName.matches(Regex("dfA\\d{3}pixel")))
            assertEquals(got.dataName.replaceFirst("dfA", "cfA"), got.controlName)
        }
    }

    @Test
    fun nonAsciiNamesAreSanitized() {
        val file = File.createTempFile("job", ".ps").apply { writeText("%!PS\n"); deleteOnExit() }
        server { port, got ->
            LpdClient("127.0.0.1", port).print("IC-Colour", "张三", "作业.pdf", file)
            assertTrue(got.control.all { it == '\n' || it in ' '..'~' })
            assertTrue(got.control.contains("\nP__\n") || got.control.contains("\nPandroid\n"))
        }
    }

    @Test
    fun reportsRejectedQueue() {
        val file = File.createTempFile("job", ".ps").apply { writeText("%!PS\n"); deleteOnExit() }
        server(rejectQueue = true) { port, _ ->
            try {
                LpdClient("127.0.0.1", port).print("NOPE", "me", "x", file)
                fail("expected rejection")
            } catch (e: LpdException) {
                assertEquals(LpdException.Reason.QUEUE_REJECTED, e.reason)
            }
        }
    }
}

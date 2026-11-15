package dev.uoftprint.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.random.Random

class LpdException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason { HOST_NOT_FOUND, CONNECT_FAILED, QUEUE_REJECTED, JOB_REJECTED, TIMEOUT, CONNECTION_LOST, CANCELLED }
}

/**
 * Minimal RFC 1179 (LPD) client. It mirrors what the CUPS `lpd` backend sends with its defaults,
 * which is the setup the UofT Linux instructions use: control file first, then the data file,
 * and the data file marked `l` (print as-is, keep control characters) so no server-side text
 * filter can touch the PostScript / PCL bytes.
 */
class LpdClient(
    private val host: String,
    private val port: Int = 515,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 120_000,
) {
    /**
     * Sends [dataFile] to [queue]. [user] becomes the job owner (P line), which print-release
     * systems show at the release station. [onProgress] gets (bytesSent, totalBytes).
     */
    fun print(
        queue: String,
        user: String,
        jobName: String,
        dataFile: File,
        clientHost: String = "android",
        isCancelled: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        val q = sanitizeAscii(queue, 128, "") { it in '!'..'~' }
        require(q.isNotEmpty()) { "Queue name is empty" }
        val owner = sanitizeUser(user)
        val hostName = sanitizeAscii(clientHost, 15, "android") { it.isLetterOrDigit() && it.code < 128 || it == '-' }
        val title = sanitizeAscii(jobName, 79, "document")
        val jobNumber = "%03d".format(Random.nextInt(1000))
        val dataName = "dfA$jobNumber$hostName"
        val controlName = "cfA$jobNumber$hostName"
        val control = buildString {
            append("H").append(hostName).append('\n')
            append("P").append(owner).append('\n')
            append("J").append(title).append('\n')
            append("l").append(dataName).append('\n')
            append("U").append(dataName).append('\n')
            append("N").append(title).append('\n')
        }.toByteArray(Charsets.US_ASCII)

        val socket = Socket()
        try {
            try {
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            } catch (e: UnknownHostException) {
                throw LpdException(LpdException.Reason.HOST_NOT_FOUND, "Can't find $host", e)
            } catch (e: SocketTimeoutException) {
                throw LpdException(LpdException.Reason.TIMEOUT, "$host:$port did not answer", e)
            } catch (e: IOException) {
                throw LpdException(LpdException.Reason.CONNECT_FAILED, "Can't connect to $host:$port", e)
            }
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
            val out = socket.getOutputStream().buffered(64 * 1024)
            val inp = socket.getInputStream()

            command(out, "\u0002$q\n")
            ack(inp, LpdException.Reason.QUEUE_REJECTED, "The server refused queue \"$q\"")

            command(out, "\u0002${control.size} $controlName\n")
            ack(inp, LpdException.Reason.JOB_REJECTED, "The server refused the job")
            out.write(control)
            out.write(0)
            out.flush()
            ack(inp, LpdException.Reason.JOB_REJECTED, "The server refused the job description")

            val total = dataFile.length()
            command(out, "\u0003$total $dataName\n")
            ack(inp, LpdException.Reason.JOB_REJECTED, "The server refused the print data (${total} bytes)")
            dataFile.inputStream().use { src ->
                val buf = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    if (isCancelled()) throw LpdException(LpdException.Reason.CANCELLED, "Cancelled")
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    onProgress(sent, total)
                }
                check(sent == total) { "Spool file changed while sending" }
            }
            out.write(0)
            out.flush()
            ack(inp, LpdException.Reason.JOB_REJECTED, "The server did not accept the print data")
        } catch (e: LpdException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw LpdException(LpdException.Reason.TIMEOUT, "The print server stopped responding", e)
        } catch (e: IOException) {
            throw LpdException(LpdException.Reason.CONNECTION_LOST, "Connection to the print server was lost", e)
        } finally {
            try { socket.close() } catch (_: IOException) {}
        }
    }

    private fun command(out: OutputStream, s: String) {
        out.write(s.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun ack(inp: InputStream, reason: LpdException.Reason, message: String) {
        when (val r = inp.read()) {
            0 -> return
            -1 -> throw LpdException(LpdException.Reason.CONNECTION_LOST, "$message (connection closed)")
            else -> throw LpdException(reason, "$message (code $r)")
        }
    }

    companion object {
        /** RFC 1179 limits the user name to 31 octets; keep it to characters every server accepts. */
        fun sanitizeUser(user: String): String =
            sanitizeAscii(user.replace(' ', '_'), 31, "android") { it.code < 128 && (it.isLetterOrDigit() || it in "._-") }
    }
}

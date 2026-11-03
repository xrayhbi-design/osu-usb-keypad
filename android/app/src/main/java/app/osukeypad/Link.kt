package app.osukeypad

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phone side of the USB link. Listens on the abstract unix socket "osukeypad"; the PC runs
 * `adb forward tcp:27183 localabstract:osukeypad` and connects, so everything travels over USB.
 *
 * Wire format, both directions: 2-byte frames [a, b].
 *   phone -> PC:  [vk, 1] key down, [vk, 0] key up (vk = Windows virtual-key code)
 *                 [0xFF, seq] ping (sent every 100 ms, doubles as heartbeat)
 *   PC -> phone:  [0xFF, seq] ping echo (used to show round-trip time)
 */
class Link(private val onChange: () -> Unit) {

    @Volatile var connected = false
        private set
    @Volatile var rttUs = -1L
        private set

    @Volatile private var running = false
    private val queue = LinkedBlockingQueue<Int>()
    private val sentAt = LongArray(256)
    private var server: LocalServerSocket? = null

    fun start() {
        if (running) return
        running = true
        Thread({ acceptLoop() }, "link-accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (e: IOException) {}
        // accept() does not always wake up on close(); poke it with a throwaway connection.
        try {
            val s = LocalSocket()
            s.connect(LocalSocketAddress(NAME))
            s.close()
        } catch (e: IOException) {}
    }

    /** Called from the touch thread. Dropped when there is no PC, so no stale events pile up. */
    fun send(vk: Int, down: Boolean) {
        if (connected) queue.offer((vk shl 8) or (if (down) 1 else 0))
    }

    private fun acceptLoop() {
        while (running) {
            val srv = try {
                LocalServerSocket(NAME)
            } catch (e: IOException) {
                Thread.sleep(200); continue
            }
            server = srv
            try {
                while (running) {
                    val s = srv.accept()
                    if (!running) { s.close(); break }
                    session(s)
                }
            } catch (e: IOException) {
                if (running) Thread.sleep(100)
            } finally {
                try { srv.close() } catch (e: IOException) {}
            }
        }
    }

    private fun session(s: LocalSocket) {
        val alive = AtomicBoolean(true)
        queue.clear()
        try { s.soTimeout = 1000 } catch (e: IOException) {}   // PC silent for 1 s = dead
        connected = true
        rttUs = -1
        onChange()
        val out = s.outputStream
        val writer = Thread({ writeLoop(out, alive) }, "link-write").apply { isDaemon = true; start() }
        try {
            val inp = s.inputStream
            val b = ByteArray(2)
            var echoes = 0
            while (running && readFull(inp, b)) {
                if ((b[0].toInt() and 0xFF) == PING) {
                    val sent = sentAt[b[1].toInt() and 0xFF]
                    val us = (System.nanoTime() - sent) / 1000
                    rttUs = if (rttUs < 0) us else (rttUs * 7 + us) / 8
                    if (++echoes % 10 == 0) onChange()
                }
            }
        } catch (e: IOException) {
        } finally {
            alive.set(false)
            connected = false
            rttUs = -1
            try { s.close() } catch (e: IOException) {}
            writer.interrupt()
            onChange()
        }
    }

    private fun writeLoop(out: java.io.OutputStream, alive: AtomicBoolean) {
        var seq = 0
        var nextPing = System.nanoTime()
        val frame = ByteArray(2)
        try {
            while (alive.get() && running) {
                val waitNs = nextPing - System.nanoTime()
                val f = if (waitNs > 0) queue.poll(waitNs, TimeUnit.NANOSECONDS) else null
                if (f != null) {
                    frame[0] = (f ushr 8).toByte()
                    frame[1] = (f and 0xFF).toByte()
                    out.write(frame)
                }
                if (System.nanoTime() >= nextPing) {
                    seq = (seq + 1) and 0xFF
                    sentAt[seq] = System.nanoTime()
                    frame[0] = PING.toByte()
                    frame[1] = seq.toByte()
                    out.write(frame)
                    nextPing = System.nanoTime() + 100_000_000L
                }
            }
        } catch (e: IOException) {
        } catch (e: InterruptedException) {
        }
    }

    private fun readFull(inp: InputStream, b: ByteArray): Boolean {
        var o = 0
        while (o < b.size) {
            val n = inp.read(b, o, b.size - o)
            if (n <= 0) return false
            o += n
        }
        return true
    }

    companion object {
        const val NAME = "osukeypad"   // must match host/OsuKeypadHost.cs
        const val PING = 0xFF
    }
}

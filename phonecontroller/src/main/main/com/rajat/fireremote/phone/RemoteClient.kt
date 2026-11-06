package com.rajat.fireremote.phone

import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors

data class AppEntry(val pkg: String, val label: String)

/**
 * Talks to the Fire TV over a plain TCP socket (see CommandServer on the TV for the protocol).
 * All listener callbacks arrive on the main thread.
 */
class RemoteClient(private val listener: Listener) {

    interface Listener {
        fun onConnected()
        fun onConnectFailed(reason: String)
        fun onDisconnected(reason: String)
        fun onApps(apps: List<AppEntry>)
    }

    companion object {
        const val PORT = 8765
        private const val PING_MS = 5000L
    }

    private class PinException : IOException("Wrong PIN, or too many wrong tries (wait a minute).")

    private class Session(val socket: Socket, val writer: BufferedWriter, val reader: BufferedReader)

    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()
    private val lock = Any()

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null

    /** Bumped on every connect()/disconnect() so stale threads can tell they are outdated. */
    @Volatile
    private var generation = 0

    @Volatile
    var isConnected = false
        private set

    private val pinger = object : Runnable {
        override fun run() {
            if (isConnected) {
                send("PING")
                main.postDelayed(this, PING_MS)
            }
        }
    }

    fun connect(host: String, pin: String) {
        val gen = synchronized(lock) {
            closeLocked()
            generation++
            generation
        }
        Thread({ doConnect(host, pin, gen) }, "remote-connect").apply { isDaemon = true }.start()
    }

    fun disconnect() {
        synchronized(lock) {
            generation++
            closeLocked()
        }
    }

    fun send(line: String) {
        if (!isConnected) return
        val gen = generation
        sender.execute {
            val w = synchronized(lock) { if (gen == generation) writer else null } ?: return@execute
            try {
                w.write(line)
                w.write("\n")
                w.flush()
            } catch (e: IOException) {
                fail(gen, "Connection lost")
            }
        }
    }

    // ------------------------------------------------------------ internals

    private fun doConnect(host: String, pin: String, gen: Int) {
        val session = try {
            handshake(host, pin)
        } catch (e: IOException) {
            if (gen == generation) main.post { listener.onConnectFailed(friendly(e)) }
            return
        }

        val accepted = synchronized(lock) {
            if (gen != generation) {
                false
            } else {
                socket = session.socket
                writer = session.writer
                isConnected = true
                true
            }
        }
        if (!accepted) {
            try {
                session.socket.close()
            } catch (_: IOException) {
            }
            return
        }

        main.post { listener.onConnected() }
        main.postDelayed(pinger, PING_MS)

        try {
            readLoop(session.reader)
        } catch (_: IOException) {
        }
        fail(gen, "Connection lost")
    }

    private fun handshake(host: String, pin: String): Session {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(host, PORT), 4000)
            s.soTimeout = 6000
            val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            w.write("HELLO $pin\n")
            w.flush()
            val answer = r.readLine() ?: throw IOException("The TV closed the connection.")
            if (answer == "DENY") throw PinException()
            if (!answer.startsWith("OK")) throw IOException("Unexpected reply from the TV.")
            // The TV answers every PING with PONG, so 12 s of silence means the link is dead.
            s.soTimeout = 12000
            return Session(s, w, r)
        } catch (e: IOException) {
            try {
                s.close()
            } catch (_: IOException) {
            }
            throw e
        }
    }

    private fun readLoop(reader: BufferedReader) {
        val apps = ArrayList<AppEntry>()
        while (true) {
            val line = reader.readLine() ?: return
            when {
                line.startsWith("APP\t") -> {
                    val p = line.split('\t')
                    if (p.size >= 2) apps.add(AppEntry(p[1], p.getOrElse(2) { p[1] }))
                }
                line == "END" -> {
                    val copy = ArrayList(apps)
                    apps.clear()
                    main.post { listener.onApps(copy) }
                }
            }
        }
    }

    private fun fail(gen: Int, reason: String) {
        val notify = synchronized(lock) { if (gen == generation) closeLocked() else false }
        if (notify) main.post { listener.onDisconnected(reason) }
    }

    /** Must be called with [lock] held. Returns true if we were connected before. */
    private fun closeLocked(): Boolean {
        val wasConnected = isConnected
        isConnected = false
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        writer = null
        main.removeCallbacks(pinger)
        return wasConnected
    }

    private fun friendly(e: IOException): String = when (e) {
        is SocketTimeoutException ->
            "Timed out. Check the IP and that phone and TV are on the same Wi-Fi."
        is ConnectException ->
            "Connection refused. Is the Fire Remote service enabled on the TV?"
        is UnknownHostException ->
            "That does not look like a valid address."
        else -> e.message ?: "Could not connect."
    }
}

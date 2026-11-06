package com.rajat.fireremote.tv

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Tiny line-based TCP server. One command per line.
 *
 * Phone -> TV:  HELLO <pin> | MOVE dx dy | CLICK | LONG | SWIPE dxPermille dyPermille
 *               DPAD UP|DOWN|LEFT|RIGHT|OK | BACK | HOME | RECENTS
 *               VOL UP|DOWN|MUTE | LAUNCH <package> | APPS | PING
 * TV -> Phone:  OK 1 | DENY | APP\t<package>\t<label> ... END | PONG
 */
class CommandServer(private val svc: RemoteAccessibilityService) {

    private companion object {
        const val TAG = "FireRemoteServer"
        const val MAX_FAILURES = 5
        const val LOCKOUT_MS = 60_000L
        const val READ_TIMEOUT_MS = 15_000
    }

    private enum class PinResult { OK, WRONG, LOCKED }

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var failures = 0
    private var lockedUntil = 0L

    fun start() {
        running = true
        Thread({ acceptLoop() }, "remote-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(RemoteAccessibilityService.PORT))
            serverSocket = ss
            Log.i(TAG, "Listening on port ${RemoteAccessibilityService.PORT}")
            while (running) {
                val client = ss.accept()
                Thread({ handleClient(client) }, "remote-client").apply { isDaemon = true }.start()
            }
        } catch (e: IOException) {
            if (running) Log.e(TAG, "Server stopped: ${e.message}")
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            // The phone pings every 5 s, so silence for 15 s means the link is dead.
            socket.soTimeout = READ_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            val reply: (String) -> Unit = { line ->
                out.write(line)
                out.write("\n")
                out.flush()
            }

            var authed = false
            while (true) {
                val line = reader.readLine() ?: break
                val parts = line.trim().split(' ', limit = 3)
                if (!authed) {
                    if (parts[0] != "HELLO") break
                    val result = checkPin(parts.getOrNull(1))
                    if (result == PinResult.OK) {
                        authed = true
                        reply("OK 1")
                        continue
                    }
                    Thread.sleep(700) // slow down guessing
                    reply("DENY")
                    break
                }
                execute(parts, reply)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client closed: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    @Synchronized
    private fun checkPin(pin: String?): PinResult {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return PinResult.LOCKED
        if (pin != null && pin == Prefs.pin(svc)) {
            failures = 0
            return PinResult.OK
        }
        failures++
        if (failures >= MAX_FAILURES) {
            lockedUntil = now + LOCKOUT_MS
            failures = 0
        }
        return PinResult.WRONG
    }

    private fun execute(parts: List<String>, reply: (String) -> Unit) {
        when (parts[0]) {
            "MOVE" -> {
                val dx = parts.getOrNull(1)?.toIntOrNull()
                val dy = parts.getOrNull(2)?.toIntOrNull()
                if (dx != null && dy != null) svc.moveCursor(dx, dy)
            }
            "CLICK" -> svc.click()
            "LONG" -> svc.longPress()
            "SWIPE" -> {
                val dx = parts.getOrNull(1)?.toIntOrNull()
                val dy = parts.getOrNull(2)?.toIntOrNull()
                if (dx != null && dy != null) svc.swipe(dx, dy)
            }
            "DPAD" -> parts.getOrNull(1)?.let { svc.dpad(it) }
            "BACK" -> svc.back()
            "HOME" -> svc.home()
            "RECENTS" -> svc.recents()
            "VOL" -> parts.getOrNull(1)?.let { svc.volume(it) }
            "LAUNCH" -> parts.getOrNull(1)?.let { svc.launchApp(it) }
            "APPS" -> {
                for ((pkg, label) in svc.listApps()) {
                    val clean = label.replace('\t', ' ').replace('\n', ' ')
                    reply("APP\t$pkg\t$clean")
                }
                reply("END")
            }
            "PING" -> reply("PONG")
            else -> Log.w(TAG, "Unknown command: ${parts[0]}")
        }
    }
}

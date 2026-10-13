package com.example.padrelay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Root relay: runs `cat /dev/input/eventN` as root and forwards the raw gamepad events
 * to the PC. Runs as a foreground service, so it keeps working with the screen off.
 */
class RelayService : Service() {

    companion object {
        const val ACTION_START = "padrelay.START"
        const val ACTION_STOP = "padrelay.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_DEVICE = "device"

        @Volatile var running = false
        @Volatile var status = ""
        @Volatile var listener: ((String) -> Unit)? = null

        private const val CHANNEL_ID = "padrelay"
        private const val NOTIF_ID = 1
    }

    private var worker: Thread? = null
    @Volatile private var stopFlag = false
    @Volatile private var suProc: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setStatus(s: String) {
        status = s
        listener?.invoke(s)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must go foreground immediately after startForegroundService()
        goForeground()

        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        val host = intent?.getStringExtra(EXTRA_HOST)
        val device = intent?.getStringExtra(EXTRA_DEVICE)
        val port = intent?.getIntExtra(EXTRA_PORT, 9876) ?: 9876
        if (host == null || device == null) {
            shutdown()
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) return START_NOT_STICKY

        stopFlag = false
        running = true
        acquireLocks()
        worker = Thread { runLoop(host, port, device) }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Controller relay", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("PadRelay is relaying your controller")
            .setContentText("Works with the screen off")
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "padrelay:cpu").apply { acquire() }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "padrelay:wifi").apply { acquire() }
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    private fun shutdown() {
        stopFlag = true
        suProc?.destroy()
        worker?.interrupt()
        releaseLocks()
        running = false
        setStatus("Stopped.")
        removeForeground()
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun removeForeground() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
    }

    override fun onDestroy() {
        stopFlag = true
        suProc?.destroy()
        releaseLocks()
        running = false
        super.onDestroy()
    }

    // ---------- worker ----------

    private fun runLoop(host: String, port: Int, deviceName: String) {
        // The kernel's input_event layout depends on the reader's bitness (cat on 64-bit Android = 24 bytes)
        val is64 = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val frameSize = if (is64) 24 else 16
        val bodyOffset = frameSize - 8

        var socket: DatagramSocket? = null
        val heartbeat = Executors.newSingleThreadScheduledExecutor()
        val state = AtomicReference<PadState?>(null)

        try {
            val sock = DatagramSocket()
            socket = sock
            val addr = InetAddress.getByName(host)

            fun send(st: PadState) {
                val d = st.packet()
                try { sock.send(DatagramPacket(d, d.size, addr, port)) } catch (_: Exception) {}
            }

            // Re-send the current state a few times a second so a lost UDP packet can't leave a button stuck
            heartbeat.scheduleAtFixedRate({ state.get()?.let { send(it) } }, 200, 200, TimeUnit.MILLISECONDS)

            while (!stopFlag) {
                setStatus("Waiting for controller \"$deviceName\" (is root granted?)...")
                val dev = Evdev.scan().firstOrNull { it.isGamepad && it.name == deviceName }
                if (dev == null) {
                    sleepQuietly(2000)
                    continue
                }

                val st = PadState(dev)
                state.set(st)
                setStatus("Relaying \"$deviceName\" to $host:$port\nThe screen can be turned off.")

                val proc = ProcessBuilder("su", "-c", "exec cat ${dev.path}").start()
                suProc = proc
                val input = DataInputStream(proc.inputStream)
                val buf = ByteArray(frameSize)
                try {
                    while (!stopFlag) {
                        input.readFully(buf)
                        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                        val type = bb.getShort(bodyOffset).toInt() and 0xFFFF
                        val code = bb.getShort(bodyOffset + 2).toInt() and 0xFFFF
                        val value = bb.getInt(bodyOffset + 4)
                        st.onEvent(type, code, value)
                        if (type == Evdev.EV_SYN && code == 0) send(st)
                    }
                } catch (_: IOException) {
                    // controller disconnected or su stream closed
                } finally {
                    proc.destroy()
                    suProc = null
                }

                st.reset()
                send(st) // release everything on the PC
                state.set(null)
                if (!stopFlag) {
                    setStatus("Controller disconnected. Waiting for it to come back...")
                    sleepQuietly(1000)
                }
            }
        } catch (e: Exception) {
            if (!stopFlag) setStatus("Error: ${e.message}")
        } finally {
            heartbeat.shutdownNow()
            socket?.close()
        }
    }

    private fun sleepQuietly(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}

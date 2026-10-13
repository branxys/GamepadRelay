package com.example.padrelay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * PadRelay: forwards a Bluetooth-connected gamepad to a PC over Wi-Fi (UDP).
 *
 * Normal mode: reads the controller through Android's input system (screen must stay on).
 * Root mode:   RelayService reads /dev/input directly as root, so it works with the screen off.
 *
 * Packet (13 bytes, little endian): magic(0xC7) buttons(u16) LT(u8) RT(u8) LX LY RX RY (s16 each)
 * Discovery: the app broadcasts "PADRELAY?" and the PC receiver replies "PADRELAY!<hostname>".
 */
class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var port = 9876
    private var running = false // normal (non-root) mode
    private var searching = false

    // Controller state for normal mode (main thread only)
    private var keyMask = 0
    private var hatMask = 0
    private var ltKey = 0
    private var rtKey = 0
    private var ltAxis = 0
    private var rtAxis = 0
    private var lx = 0
    private var ly = 0
    private var rx = 0
    private var ry = 0

    private val padNames = mutableListOf<String>()

    private lateinit var ipField: EditText
    private lateinit var portField: EditText
    private lateinit var findButton: Button
    private lateinit var rootSwitch: Switch
    private lateinit var padSpinner: Spinner
    private lateinit var scanButton: Button
    private lateinit var toggle: Button
    private lateinit var status: TextView

    private val prefs by lazy { getSharedPreferences("padrelay", MODE_PRIVATE) }

    private fun anyRunning() = running || RelayService.running

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val pad = (16 * resources.displayMetrics.density).toInt()

        ipField = EditText(this).apply {
            hint = "PC IP address (auto-detected)"
            setText(prefs.getString("ip", ""))
            setSingleLine()
        }
        portField = EditText(this).apply {
            hint = "Port"
            setText(prefs.getString("port", "9876"))
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        findButton = Button(this).apply {
            text = "Find PC"
            setOnClickListener { findPc(null) }
        }
        padSpinner = Spinner(this)
        scanButton = Button(this).apply {
            text = "Scan controllers (needs root)"
            setOnClickListener { scanControllers() }
        }
        rootSwitch = Switch(this).apply {
            text = "Root mode (works with screen off)"
            isChecked = prefs.getBoolean("root", false)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean("root", on).apply()
                updateRootUi(on)
                if (on && padNames.isEmpty()) scanControllers()
            }
        }
        toggle = Button(this).apply {
            text = "Start relay"
            setOnClickListener {
                when {
                    running -> stopRelay()
                    RelayService.running -> stopRootRelay()
                    rootSwitch.isChecked -> startRootRelay()
                    else -> startRelay()
                }
            }
        }
        status = TextView(this).apply {
            text = "Pair your controller in Android Bluetooth settings, and run the receiver on your PC."
            textSize = 15f
            setPadding(0, pad, 0, 0)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(ipField)
            addView(portField)
            addView(findButton)
            addView(rootSwitch)
            addView(padSpinner)
            addView(scanButton)
            addView(toggle)
            addView(status)
        })

        updateRootUi(rootSwitch.isChecked)
        if (rootSwitch.isChecked) scanControllers()

        // Search automatically on launch
        findPc(null)
    }

    override fun onResume() {
        super.onResume()
        RelayService.listener = { s -> runOnUiThread { status.text = s; refreshButtons() } }
        if (RelayService.running) status.text = RelayService.status
        refreshButtons()
    }

    override fun onPause() {
        RelayService.listener = null
        super.onPause()
    }

    private fun refreshButtons() {
        toggle.text = if (anyRunning()) "Stop relay" else "Start relay"
        findButton.isEnabled = !anyRunning() && !searching
        scanButton.isEnabled = !anyRunning()
        rootSwitch.isEnabled = !anyRunning()
        padSpinner.isEnabled = !anyRunning()
    }

    private fun updateRootUi(on: Boolean) {
        val v = if (on) View.VISIBLE else View.GONE
        padSpinner.visibility = v
        scanButton.visibility = v
    }

    // ---------- discovery ----------

    /** Broadcasts a discovery request and fills the IP field with the first PC that answers. */
    private fun findPc(then: (() -> Unit)?) {
        if (searching || anyRunning()) return
        val p = portField.text.toString().toIntOrNull() ?: 9876
        searching = true
        refreshButtons()
        status.text = "Searching for PC on your network..."

        Thread {
            var foundIp: String? = null
            var foundName = ""
            try {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    s.soTimeout = 500
                    val msg = "PADRELAY?".toByteArray()

                    // Global broadcast plus each network interface's own broadcast address
                    val targets = mutableSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
                    NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                        if (ni.isUp && !ni.isLoopback) {
                            ni.interfaceAddresses.forEach { ia -> ia.broadcast?.let { targets.add(it) } }
                        }
                    }

                    val end = System.currentTimeMillis() + 5000
                    val buf = ByteArray(128)
                    while (foundIp == null && System.currentTimeMillis() < end) {
                        for (t in targets) {
                            try { s.send(DatagramPacket(msg, msg.size, t, p)) } catch (_: Exception) {}
                        }
                        try {
                            val pkt = DatagramPacket(buf, buf.size)
                            s.receive(pkt)
                            val text = String(pkt.data, 0, pkt.length)
                            if (text.startsWith("PADRELAY!")) {
                                foundIp = pkt.address.hostAddress
                                foundName = text.removePrefix("PADRELAY!")
                            }
                        } catch (_: SocketTimeoutException) {
                        }
                    }
                }
            } catch (_: Exception) {
            }

            runOnUiThread {
                searching = false
                refreshButtons()
                if (foundIp != null) {
                    ipField.setText(foundIp)
                    status.text = "Found PC${if (foundName.isNotEmpty()) " \"$foundName\"" else ""} at $foundIp"
                    then?.invoke()
                } else {
                    status.text = "No PC found. Make sure pc_receiver.py is running on the same Wi-Fi " +
                        "and UDP port $p is allowed through the firewall, or enter the IP manually."
                }
            }
        }.start()
    }

    // ---------- root mode ----------

    private fun scanControllers() {
        status.text = "Scanning controllers... (grant root access if asked)"
        Thread {
            val names = try {
                Evdev.scan().filter { it.isGamepad }.map { it.name }.distinct()
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                padNames.clear()
                padNames.addAll(names)
                padSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, padNames)
                val saved = prefs.getString("pad", null)
                val idx = padNames.indexOf(saved)
                if (idx >= 0) padSpinner.setSelection(idx)
                status.text = if (names.isEmpty()) {
                    "No controller found. Check that root was granted and the controller is connected, then scan again."
                } else {
                    "Found ${names.size} controller(s). Select yours and tap Start relay."
                }
            }
        }.start()
    }

    private fun startRootRelay() {
        val host = ipField.text.toString().trim()
        val p = portField.text.toString().toIntOrNull()
        if (p == null) {
            status.text = "Enter a valid port."
            return
        }
        val pad = padSpinner.selectedItem as? String
        if (pad == null) {
            status.text = "Scan and select your controller first."
            return
        }
        if (host.isEmpty()) {
            findPc { startRootRelay() }
            return
        }
        prefs.edit().putString("ip", host).putString("port", p.toString()).putString("pad", pad).apply()
        val i = Intent(this, RelayService::class.java).apply {
            action = RelayService.ACTION_START
            putExtra(RelayService.EXTRA_HOST, host)
            putExtra(RelayService.EXTRA_PORT, p)
            putExtra(RelayService.EXTRA_DEVICE, pad)
        }
        ContextCompat.startForegroundService(this, i)
        status.text = "Starting root relay..."
        refreshButtons()
    }

    private fun stopRootRelay() {
        startService(Intent(this, RelayService::class.java).setAction(RelayService.ACTION_STOP))
    }

    // ---------- normal mode: start / stop ----------

    private fun startRelay() {
        val host = ipField.text.toString().trim()
        val p = portField.text.toString().toIntOrNull()
        if (p == null) {
            status.text = "Enter a valid port."
            return
        }
        if (host.isEmpty()) {
            // No IP yet: search first, then start automatically if something answers
            findPc { startRelay() }
            return
        }
        prefs.edit().putString("ip", host).putString("port", p.toString()).apply()
        port = p
        status.text = "Connecting..."
        io.execute {
            try {
                socket = DatagramSocket()
                address = InetAddress.getByName(host)
                runOnUiThread {
                    running = true
                    refreshButtons()
                    status.text = "Relaying to $host:$port\nKeep this app open and press buttons on your controller."
                    send()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Error: ${e.message}" }
            }
        }
    }

    private fun stopRelay() {
        resetState()
        send() // tell the PC everything is released
        running = false
        io.execute { socket?.close(); socket = null }
        refreshButtons()
        status.text = "Stopped."
    }

    private fun resetState() {
        keyMask = 0; hatMask = 0; ltKey = 0; rtKey = 0; ltAxis = 0; rtAxis = 0
        lx = 0; ly = 0; rx = 0; ry = 0
    }

    override fun onDestroy() {
        if (running) stopRelay()
        io.shutdown()
        super.onDestroy()
    }

    // ---------- normal mode: input handling ----------

    private fun keyToBit(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> 0x0001
        KeyEvent.KEYCODE_DPAD_DOWN -> 0x0002
        KeyEvent.KEYCODE_DPAD_LEFT -> 0x0004
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0x0008
        KeyEvent.KEYCODE_BUTTON_START -> 0x0010
        KeyEvent.KEYCODE_BUTTON_SELECT -> 0x0020
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 0x0040
        KeyEvent.KEYCODE_BUTTON_THUMBR -> 0x0080
        KeyEvent.KEYCODE_BUTTON_L1 -> 0x0100
        KeyEvent.KEYCODE_BUTTON_R1 -> 0x0200
        KeyEvent.KEYCODE_BUTTON_MODE -> 0x0400
        KeyEvent.KEYCODE_BUTTON_A -> 0x1000
        KeyEvent.KEYCODE_BUTTON_B -> 0x2000
        KeyEvent.KEYCODE_BUTTON_X -> 0x4000
        KeyEvent.KEYCODE_BUTTON_Y -> 0x8000
        else -> 0
    }

    private fun isGamepad(source: Int) =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (running && isGamepad(event.source)) {
            val down = event.action == KeyEvent.ACTION_DOWN
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return true

            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_L2 -> { ltKey = if (down) 255 else 0; send(); return true }
                KeyEvent.KEYCODE_BUTTON_R2 -> { rtKey = if (down) 255 else 0; send(); return true }
            }
            val bit = keyToBit(event.keyCode)
            if (bit != 0) {
                keyMask = if (down) keyMask or bit else keyMask and bit.inv()
                send()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Stick value with the device's own deadzone applied; null if the axis doesn't exist. */
    private fun stick(e: MotionEvent, axis: Int): Float? {
        val range = e.device?.getMotionRange(axis, e.source) ?: return null
        val v = e.getAxisValue(axis)
        return if (abs(v) <= max(range.flat, 0.08f)) 0f else v
    }

    private fun trigger(e: MotionEvent, axis: Int): Float? {
        e.device?.getMotionRange(axis, e.source) ?: return null
        return e.getAxisValue(axis)
    }

    private fun toS16(v: Float) = (v * 32767f).toInt().coerceIn(-32768, 32767)
    private fun toU8(v: Float) = (v * 255f).toInt().coerceIn(0, 255)

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (running &&
            (e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            e.action == MotionEvent.ACTION_MOVE
        ) {
            // Left stick (Android Y is down-positive, XInput is up-positive, so invert)
            lx = toS16(stick(e, MotionEvent.AXIS_X) ?: 0f)
            ly = toS16(-(stick(e, MotionEvent.AXIS_Y) ?: 0f))

            // Right stick: most Xbox-style pads use Z/RZ, some use RX/RY
            val hasZ = e.device?.getMotionRange(MotionEvent.AXIS_Z, e.source) != null
            val rxAxis = if (hasZ) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX
            val ryAxis = if (hasZ) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY
            rx = toS16(stick(e, rxAxis) ?: 0f)
            ry = toS16(-(stick(e, ryAxis) ?: 0f))

            // Triggers
            val l = trigger(e, MotionEvent.AXIS_LTRIGGER) ?: trigger(e, MotionEvent.AXIS_BRAKE)
            val r = trigger(e, MotionEvent.AXIS_RTRIGGER) ?: trigger(e, MotionEvent.AXIS_GAS)
            if (l != null) ltAxis = toU8(l)
            if (r != null) rtAxis = toU8(r)

            // D-pad as hat axes
            val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
            var hat = 0
            if (hy < -0.5f) hat = hat or 0x0001
            if (hy > 0.5f) hat = hat or 0x0002
            if (hx < -0.5f) hat = hat or 0x0004
            if (hx > 0.5f) hat = hat or 0x0008
            hatMask = hat

            send()
            return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    // ---------- normal mode: networking ----------

    private fun send() {
        if (!running) return
        val data = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0xC7.toByte())
            putShort((keyMask or hatMask).toShort())
            put(max(ltKey, ltAxis).toByte())
            put(max(rtKey, rtAxis).toByte())
            putShort(lx.toShort())
            putShort(ly.toShort())
            putShort(rx.toShort())
            putShort(ry.toShort())
        }.array()

        io.execute {
            try {
                val s = socket
                val a = address
                if (s != null && a != null) s.send(DatagramPacket(data, data.size, a, port))
            } catch (_: Exception) {
            }
        }
    }
}

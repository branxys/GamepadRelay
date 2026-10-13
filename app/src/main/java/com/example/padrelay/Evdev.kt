package com.example.padrelay

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

data class AbsInfo(val min: Int, val max: Int)

data class PadDevice(
    val path: String,
    val name: String,
    val abs: Map<Int, AbsInfo>, // evdev ABS code -> range
    val isGamepad: Boolean
)

/** Reads the kernel's raw input devices as root (works with the screen off). */
object Evdev {
    const val EV_SYN = 0
    const val EV_KEY = 1
    const val EV_ABS = 3

    private val ABS_CODES = mapOf(
        "ABS_X" to 0, "ABS_Y" to 1, "ABS_Z" to 2,
        "ABS_RX" to 3, "ABS_RY" to 4, "ABS_RZ" to 5,
        "ABS_GAS" to 9, "ABS_BRAKE" to 10,
        "ABS_HAT0X" to 16, "ABS_HAT0Y" to 17
    )

    // Key names that only gamepads/joysticks report (mice and touchscreens don't)
    private val GAMEPAD_KEYS = setOf(
        "BTN_GAMEPAD", "BTN_SOUTH", "BTN_A", "BTN_EAST", "BTN_B", "BTN_NORTH", "BTN_X",
        "BTN_WEST", "BTN_Y", "BTN_TL", "BTN_TR", "BTN_JOYSTICK", "BTN_TRIGGER"
    )

    private val sectionRe = Regex("""^\s*([A-Z]+)\s+\([0-9a-fA-F]+\):""")
    private val absRe = Regex("""(ABS_\w+)\s*:\s*value\s+-?\d+,\s*min\s+(-?\d+),\s*max\s+(-?\d+)""")

    fun runSu(cmd: String): String = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (e: IOException) {
        ""
    }

    /** Lists all input devices (needs root). Parses `getevent -lp`. */
    fun scan(): List<PadDevice> {
        val devices = mutableListOf<PadDevice>()
        var path: String? = null
        var name = ""
        var abs = mutableMapOf<Int, AbsInfo>()
        var hasPadKey = false
        var section = ""

        fun flush() {
            val p = path ?: return
            val isPad = hasPadKey && abs.containsKey(0) && abs.containsKey(1)
            devices += PadDevice(p, name, abs.toMap(), isPad)
        }

        for (line in runSu("getevent -lp").lineSequence()) {
            if (line.startsWith("add device")) {
                flush()
                path = line.substringAfter(":").trim()
                name = ""
                abs = mutableMapOf()
                hasPadKey = false
                section = ""
                continue
            }
            if (line.trimStart().startsWith("name:")) {
                name = line.substringAfter("\"", "").substringBeforeLast("\"")
                continue
            }
            sectionRe.find(line)?.let { section = it.groupValues[1] }
            when (section) {
                "KEY" -> if (line.split(Regex("\\s+")).any { it in GAMEPAD_KEYS }) hasPadKey = true
                "ABS" -> absRe.find(line)?.let { m ->
                    val code = ABS_CODES[m.groupValues[1]]
                    if (code != null) abs[code] = AbsInfo(m.groupValues[2].toInt(), m.groupValues[3].toInt())
                }
            }
        }
        flush()
        return devices
    }
}

/**
 * Translates raw evdev events into an XInput-style state and 13-byte UDP packet:
 * magic(0xC7) buttons(u16) LT(u8) RT(u8) LX LY RX RY (s16 each), little endian.
 */
class PadState(dev: PadDevice) {

    companion object {
        // evdev key code -> XInput button bit. Edit here if your controller's buttons come out wrong.
        private val KEYMAP = mapOf(
            0x130 to 0x1000, // BTN_SOUTH / A
            0x131 to 0x2000, // BTN_EAST  / B
            0x133 to 0x4000, // BTN_NORTH / X
            0x134 to 0x8000, // BTN_WEST  / Y
            0x136 to 0x0100, // BTN_TL  (LB)
            0x137 to 0x0200, // BTN_TR  (RB)
            0x13a to 0x0020, // BTN_SELECT (Back)
            0x13b to 0x0010, // BTN_START
            0x13c to 0x0400, // BTN_MODE (Guide)
            0x13d to 0x0040, // BTN_THUMBL
            0x13e to 0x0080, // BTN_THUMBR
            0x220 to 0x0001, // BTN_DPAD_UP
            0x221 to 0x0002, // BTN_DPAD_DOWN
            0x222 to 0x0004, // BTN_DPAD_LEFT
            0x223 to 0x0008  // BTN_DPAD_RIGHT
        )
        private const val BTN_TL2 = 0x138
        private const val BTN_TR2 = 0x139
    }

    private val abs = dev.abs

    // Right stick is RX/RY if the pad has them, otherwise Z/RZ (generic HID pads)
    private val hasRight = abs.containsKey(3) && abs.containsKey(4)
    private val rsX = if (hasRight) 3 else 2
    private val rsY = if (hasRight) 4 else 5
    private val ltCode = if (hasRight && abs.containsKey(2)) 2 else if (abs.containsKey(10)) 10 else -1
    private val rtCode = if (hasRight && abs.containsKey(5)) 5 else if (abs.containsKey(9)) 9 else -1

    private var keyMask = 0
    private var hatX = 0
    private var hatY = 0
    private var ltBtn = false
    private var rtBtn = false
    private var lt = 0
    private var rt = 0
    private var lx = 0
    private var ly = 0
    private var rx = 0
    private var ry = 0

    private fun stick(v: Int, i: AbsInfo): Int {
        val center = (i.min + i.max) / 2.0
        val half = max((i.max - i.min) / 2.0, 1.0)
        var n = (v - center) / half
        if (abs(n) < 0.08) n = 0.0
        return (n * 32767).toInt().coerceIn(-32767, 32767)
    }

    private fun trigger(v: Int, i: AbsInfo): Int {
        val range = max(i.max - i.min, 1)
        return ((v - i.min) * 255 / range).coerceIn(0, 255)
    }

    @Synchronized
    fun onEvent(type: Int, code: Int, value: Int) {
        if (type == Evdev.EV_KEY) {
            when (code) {
                BTN_TL2 -> ltBtn = value != 0
                BTN_TR2 -> rtBtn = value != 0
                else -> KEYMAP[code]?.let { bit ->
                    keyMask = if (value != 0) keyMask or bit else keyMask and bit.inv()
                }
            }
        } else if (type == Evdev.EV_ABS) {
            val info = abs[code] ?: return
            when {
                code == 0 -> lx = stick(value, info)
                code == 1 -> ly = -stick(value, info) // evdev up is negative, XInput up is positive
                code == rsX -> rx = stick(value, info)
                code == rsY -> ry = -stick(value, info)
                code == ltCode -> lt = trigger(value, info)
                code == rtCode -> rt = trigger(value, info)
                code == 16 -> hatX = value
                code == 17 -> hatY = value
            }
        }
    }

    @Synchronized
    fun reset() {
        keyMask = 0; hatX = 0; hatY = 0; ltBtn = false; rtBtn = false
        lt = 0; rt = 0; lx = 0; ly = 0; rx = 0; ry = 0
    }

    @Synchronized
    fun packet(): ByteArray {
        var hat = 0
        if (hatY < 0) hat = hat or 0x0001
        if (hatY > 0) hat = hat or 0x0002
        if (hatX < 0) hat = hat or 0x0004
        if (hatX > 0) hat = hat or 0x0008
        return ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0xC7.toByte())
            putShort((keyMask or hat).toShort())
            put(max(lt, if (ltBtn) 255 else 0).toByte())
            put(max(rt, if (rtBtn) 255 else 0).toByte())
            putShort(lx.toShort())
            putShort(ly.toShort())
            putShort(rx.toShort())
            putShort(ry.toShort())
        }.array()
    }
}

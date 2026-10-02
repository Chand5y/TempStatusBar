package com.personal.tempstatusbar

import android.media.AudioManager
import android.media.ToneGenerator
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object HardwareThermalControl {

    private var rootProcess: Process? = null
    private var rootWriter: OutputStreamWriter? = null
    private var rootReader: BufferedReader? = null
    var isRootPipeReady = false
        private set

    private var toneGen: ToneGenerator? = null
    var isChargingThrottled = false
        private set

    fun init() {
        ensureRootPipe()
        try {
            toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (e: Exception) {}
    }

    @Synchronized
    fun ensureRootPipe(): Boolean {
        if (isRootPipeReady && isProcessAlive(rootProcess)) {
            return true
        }
        destroy()
        return try {
            val p = Runtime.getRuntime().exec("su")
            val writer = OutputStreamWriter(p.outputStream)
            val reader = BufferedReader(InputStreamReader(p.inputStream))

            // Verify genuine root privilege via id check
            writer.write("id\n")
            writer.flush()

            val line = reader.readLine()
            if (line != null && line.contains("uid=0")) {
                rootProcess = p
                rootWriter = writer
                rootReader = reader
                isRootPipeReady = true
                true
            } else {
                p.destroy()
                isRootPipeReady = false
                false
            }
        } catch (e: Exception) {
            isRootPipeReady = false
            false
        }
    }

    private fun isProcessAlive(p: Process?): Boolean {
        if (p == null) return false
        return try {
            p.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    fun setChargingEnabled(enable: Boolean) {
        if (!ensureRootPipe()) return
        val value = if (enable) "1" else "0"
        val suspendVal = if (enable) "0" else "1"

        try {
            val cmd = """
                echo $value > /sys/class/power_supply/battery/charging_enabled 2>/dev/null
                echo $suspendVal > /sys/class/power_supply/battery/input_suspend 2>/dev/null
                echo $value > /sys/class/power_supply/bms/charging_enabled 2>/dev/null
            """.trimIndent()
            
            rootWriter?.write(cmd + "\n")
            rootWriter?.flush()
            isChargingThrottled = !enable
        } catch (e: Exception) {
            destroy()
        }
    }

    fun playThermalAlert() {
        try {
            toneGen?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500)
        } catch (e: Exception) {}
    }

    // Android 15 / Toybox compliant instant snapshot
    fun getKernelProcessSnapshot(): String {
        if (!ensureRootPipe()) return ""
        return try {
            rootWriter?.write("top -n 1 -m 6\necho '__END__'\n")
            rootWriter?.flush()

            val sb = StringBuilder()
            var line: String?
            var headerPassed = false
            var count = 0

            while (rootReader?.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (l.contains("__END__")) break

                if (l.contains("PID") && (l.contains("ARGS") || l.contains("NAME") || l.contains("CMD"))) {
                    headerPassed = true
                    continue
                }

                if (headerPassed && l.isNotEmpty()) {
                    val parts = l.split("\\s+".toRegex())
                    val procName = parts.last()
                    // Filter out kernel worker bracket tasks to highlight real apps
                    if (!procName.startsWith("[") && !procName.startsWith("top")) {
                        sb.append("• ").append(procName).append("\n")
                        count++
                        if (count >= 5) break
                    }
                }
            }
            sb.toString().trim()
        } catch (e: Exception) {
            destroy()
            ""
        }
    }

    fun destroy() {
        try {
            rootWriter?.write("exit\n")
            rootWriter?.flush()
        } catch (e: Exception) {}
        try { rootProcess?.destroy() } catch (e: Exception) {}
        rootProcess = null
        rootWriter = null
        rootReader = null
        isRootPipeReady = false
    }
}

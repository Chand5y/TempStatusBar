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
    private var isRootPipeReady = false

    private var toneGen: ToneGenerator? = null
    var isChargingThrottled = false
        private set

    fun init() {
        initRootPipe()
        try {
            toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (e: Exception) {}
    }

    private fun initRootPipe() {
        try {
            rootProcess = Runtime.getRuntime().exec("su")
            rootWriter = OutputStreamWriter(rootProcess!!.outputStream)
            rootReader = BufferedReader(InputStreamReader(rootProcess!!.inputStream))
            isRootPipeReady = true
        } catch (e: Exception) {
            isRootPipeReady = false
        }
    }

    // Direct PMIC Hardware Switch: Cuts electricity at the board level
    fun setChargingEnabled(enable: Boolean) {
        if (!isRootPipeReady) return
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
            initRootPipe()
        }
    }

    // Play instant low-level hardware warning tone without loading media frameworks
    fun playThermalAlert() {
        try {
            toneGen?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500)
        } catch (e: Exception) {}
    }

    // Capture top 5 CPU processes through the persistent pipe (Zero fork overhead)
    fun getKernelProcessSnapshot(): String {
        if (!isRootPipeReady) return ""
        return try {
            rootWriter?.write("top -b -n 1 -m 5 -s cpu\necho '__END__'\n")
            rootWriter?.flush()

            val sb = StringBuilder()
            var line: String?
            while (rootReader?.readLine().also { line = it } != null) {
                if (line!!.contains("__END__")) break
                val l = line!!.trim()
                if (l.isNotEmpty() && !l.startsWith("Tasks:") && !l.startsWith("Mem:")) {
                    sb.append(l).append("\n")
                }
            }
            sb.toString().trim()
        } catch (e: Exception) {
            initRootPipe()
            ""
        }
    }

    fun destroy() {
        try {
            setChargingEnabled(true)
            rootWriter?.write("exit\n")
            rootWriter?.flush()
            rootProcess?.destroy()
            toneGen?.release()
        } catch (e: Exception) {}
    }
}

    private fun triggerDatabaseSnapshot(temp: Int, isCharging: Boolean, screenOn: Boolean) {
        backgroundHandler.post {
            val chargeType = if (isCharging) "Charging Connected" else "Discharging (Battery)"
            val hasRoot = HardwareThermalControl.isRootAvailable()
            val details = if (hasRoot) {
                val snapshotList = HardwareThermalControl.getKernelProcessSnapshot(applicationContext)
                if (snapshotList.isNotEmpty()) {
                    snapshotList.joinToString("\n") { "• ${it.name} — ${it.cpu}% CPU" }
                } else "Kernel active (Idle)"
            } else {
                ProcessInspector.captureNonRootActiveApps(applicationContext)
            }
            dbHelper.insertRecord(temp, isCharging, chargeType, details, hasRoot, screenOn)
        }
    }

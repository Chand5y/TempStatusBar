    private fun handleThermalSafety(temp: Int, isCharging: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val safeTemp = settings.warningTemp - 1

        if (temp >= settings.warningTemp) {
            HardwareThermalControl.playThermalAlert()
            
            if (isSmartGovernorActive) {
                backgroundHandler.post { HardwareThermalControl.applySmartThermalGovernor(applicationContext) }
            }

            val muteIntent = PendingIntent.getBroadcast(this, 0, Intent(this, ThermalActionReceiver::class.java).apply { action = "ACTION_MUTE" }, PendingIntent.FLAG_IMMUTABLE)
            val coolIntent = PendingIntent.getBroadcast(this, 1, Intent(this, ThermalActionReceiver::class.java).apply { action = "ACTION_COOLDOWN" }, PendingIntent.FLAG_IMMUTABLE)

            val alertNotif = Notification.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle("⚠️ OVERHEATING: ${temp}°C")
                .setContentText(if (isSmartGovernorActive) "Smart Governor Actively Throttling..." else "Hardware limits exceeded.")
                .setSmallIcon(drawIcon("!"))
                .setColor(Color.RED)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "MUTE SOUND", muteIntent).build())
                .addAction(Notification.Action.Builder(null, "COOL DOWN NOW", coolIntent).build())
                .setContentIntent(getLaunchIntent())
                .build()
            manager?.notify(ALERT_NOTIF_ID, alertNotif)
        } else if (temp <= safeTemp) {
            manager?.cancel(ALERT_NOTIF_ID)
            HardwareThermalControl.resetMute()
            if (HardwareThermalControl.isEmergencyCooldownActive) {
                HardwareThermalControl.clearEmergencyCooldown()
                if (!HardwareThermalControl.isManualBypassActive) {
                    HardwareThermalControl.setChargingEnabled(true)
                }
            }
            if (isSmartGovernorActive) {
                backgroundHandler.post { HardwareThermalControl.throttlePrimeCore(false) }
            }
        }

        // Cut off charging when too hot
        if (temp >= settings.cutoffTemp && isCharging && !HardwareThermalControl.isChargingThrottled && !HardwareThermalControl.isEmergencyCooldownActive) {
            HardwareThermalControl.setChargingEnabled(false)
        } 
        // Only auto-resume charging if the user hasn't explicitly locked on manual bypass
        else if (temp <= settings.resumeTemp && HardwareThermalControl.isChargingThrottled && !HardwareThermalControl.isEmergencyCooldownActive && !HardwareThermalControl.isManualBypassActive) {
            HardwareThermalControl.setChargingEnabled(true)
        }
    }

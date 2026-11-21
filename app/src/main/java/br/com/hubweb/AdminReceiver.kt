package br.com.hubweb

import android.app.admin.DeviceAdminReceiver

/** Só existe para permitir `dpm set-device-owner` (modo kiosk completo). */
class AdminReceiver : DeviceAdminReceiver()

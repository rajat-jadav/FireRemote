package com.rajat.fireremote.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/** Status screen shown on the TV: is the service on, what to type on the phone. */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var addressText: TextView
    private lateinit var pinText: TextView

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        addressText = findViewById(R.id.address_text)
        pinText = findViewById(R.id.pin_text)

        findViewById<Button>(R.id.btn_settings).setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.btn_new_pin).setOnClickListener {
            Prefs.newPin(this)
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh.run()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun render() {
        val on = RemoteAccessibilityService.instance != null
        statusText.text = getString(if (on) R.string.status_on else R.string.status_off)
        statusText.setTextColor(if (on) Color.parseColor("#4CAF50") else Color.parseColor("#FF5252"))
        val ip = Net.localIp() ?: "not connected to Wi-Fi"
        addressText.text = getString(R.string.address_fmt, ip, RemoteAccessibilityService.PORT)
        pinText.text = Prefs.pin(this)
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.settings_missing, Toast.LENGTH_LONG).show()
        }
    }
}

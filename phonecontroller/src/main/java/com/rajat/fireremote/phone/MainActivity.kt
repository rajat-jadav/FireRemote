package com.rajat.fireremote.phone

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.rajat.fireremote.phone.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), RemoteClient.Listener, TouchpadView.Callback {

    private enum class State { IDLE, CONNECTING, CONNECTED }

    private companion object {
        const val PREFS = "fireremote_phone"
        const val KEY_IP = "ip"
        const val KEY_PIN = "pin"
        const val KEY_AUTO = "auto_connect"
        const val KEY_SENS = "sensitivity"
        const val KEY_PINNED = "pinned_apps"
        const val DEFAULT_SENS = 30
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var appsAdapter: AppsAdapter
    private val client by lazy { RemoteClient(this) }
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    private var connectedIp = ""

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        b.ipInput.setText(prefs.getString(KEY_IP, ""))
        b.pinInput.setText(prefs.getString(KEY_PIN, ""))
        b.btnConnect.setOnClickListener { toggleConnection() }

        setupModes()
        setupTouchpad()
        setupButtons()
        setupApps()
        setState(State.IDLE, getString(R.string.status_idle))
    }

    override fun onStart() {
        super.onStart()
        val ip = prefs.getString(KEY_IP, "").orEmpty()
        val pin = prefs.getString(KEY_PIN, "").orEmpty()
        if (state == State.IDLE && prefs.getBoolean(KEY_AUTO, false) && ip.isNotEmpty() && pin.length == 6) {
            connectTo(ip, pin)
        }
    }

    override fun onStop() {
        super.onStop()
        client.disconnect()
        setState(State.IDLE, getString(R.string.status_idle))
    }

    // ----------------------------------------------------------- connection

    private fun toggleConnection() {
        if (state != State.IDLE) {
            prefs.edit().putBoolean(KEY_AUTO, false).apply()
            client.disconnect()
            setState(State.IDLE, getString(R.string.status_disconnected))
            return
        }
        val ip = b.ipInput.text?.toString()?.trim().orEmpty()
        val pin = b.pinInput.text?.toString()?.trim().orEmpty()
        if (ip.isEmpty() || pin.length != 6) {
            Toast.makeText(this, R.string.enter_details, Toast.LENGTH_LONG).show()
            return
        }
        currentFocus?.let {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
        connectTo(ip, pin)
    }

    private fun connectTo(ip: String, pin: String) {
        prefs.edit().putString(KEY_IP, ip).putString(KEY_PIN, pin).apply()
        connectedIp = ip
        setState(State.CONNECTING, getString(R.string.status_connecting, ip))
        client.connect(ip, pin)
    }

    private fun setState(newState: State, message: String) {
        state = newState
        b.statusText.text = message
        val colorRes = when (newState) {
            State.CONNECTED -> R.color.ok_green
            State.CONNECTING -> R.color.accent
            State.IDLE -> R.color.muted
        }
        b.statusText.setTextColor(ContextCompat.getColor(this, colorRes))
        b.btnConnect.setText(if (newState == State.IDLE) R.string.connect else R.string.disconnect)
    }

    override fun onConnected() {
        prefs.edit().putBoolean(KEY_AUTO, true).apply()
        setState(State.CONNECTED, getString(R.string.status_connected, connectedIp))
        client.send("APPS")
    }

    override fun onConnectFailed(reason: String) {
        setState(State.IDLE, reason)
    }

    override fun onDisconnected(reason: String) {
        setState(State.IDLE, reason)
    }

    override fun onApps(apps: List<AppEntry>) {
        appsAdapter.submit(apps)
    }

    private fun send(command: String) {
        if (client.isConnected) client.send(command)
    }

    // ----------------------------------------------------------------- UI

    private fun setupModes() {
        b.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            b.panelTouchpad.visibility = if (checkedId == R.id.btn_mode_touchpad) View.VISIBLE else View.GONE
            b.panelDpad.visibility = if (checkedId == R.id.btn_mode_dpad) View.VISIBLE else View.GONE
            b.panelApps.visibility = if (checkedId == R.id.btn_mode_apps) View.VISIBLE else View.GONE
        }
        b.modeGroup.check(R.id.btn_mode_touchpad)
    }

    private fun setupTouchpad() {
        b.touchpad.callback = this
        b.sensSeek.progress = prefs.getInt(KEY_SENS, DEFAULT_SENS)
        b.touchpad.sensitivity = sensitivityFor(b.sensSeek.progress)
        b.sensSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                b.touchpad.sensitivity = sensitivityFor(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.edit().putInt(KEY_SENS, seekBar?.progress ?: DEFAULT_SENS).apply()
            }
        })
    }

    private fun sensitivityFor(progress: Int): Float = 0.6f + progress * 0.04f

    private fun setupButtons() {
        // Arrows and volume repeat while held; OK, Back, Home and Mute fire once.
        attachRepeat(b.btnUp, "DPAD UP", repeat = true)
        attachRepeat(b.btnDown, "DPAD DOWN", repeat = true)
        attachRepeat(b.btnLeft, "DPAD LEFT", repeat = true)
        attachRepeat(b.btnRight, "DPAD RIGHT", repeat = true)
        attachRepeat(b.btnOk, "DPAD OK", repeat = false)
        attachRepeat(b.btnBack, "BACK", repeat = false)
        attachRepeat(b.btnHome, "HOME", repeat = false)
        attachRepeat(b.btnVolDown, "VOL DOWN", repeat = true)
        attachRepeat(b.btnVolUp, "VOL UP", repeat = true)
        attachRepeat(b.btnMute, "VOL MUTE", repeat = false)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachRepeat(view: View, command: String, repeat: Boolean) {
        var repeater: Runnable? = null
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    send(command)
                    if (repeat) {
                        val r = object : Runnable {
                            override fun run() {
                                send(command)
                                main.postDelayed(this, 110)
                            }
                        }
                        repeater = r
                        main.postDelayed(r, 400)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeater?.let { main.removeCallbacks(it) }
                    repeater = null
                }
            }
            false // let the button draw its pressed state
        }
    }

    private fun setupApps() {
        appsAdapter = AppsAdapter(
            onLaunch = { app -> send("LAUNCH ${app.pkg}") },
            onPinsChanged = { pins -> prefs.edit().putStringSet(KEY_PINNED, HashSet(pins)).apply() },
            initialPins = prefs.getStringSet(KEY_PINNED, emptySet()).orEmpty().toSet()
        )
        b.appsList.layoutManager = LinearLayoutManager(this)
        b.appsList.adapter = appsAdapter
        b.btnRefreshApps.setOnClickListener { send("APPS") }
    }

    // ------------------------------------------- phone volume keys -> TV volume

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (client.isConnected) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    send("VOL UP")
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    send("VOL DOWN")
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------- touchpad callbacks

    override fun onMove(dx: Int, dy: Int) = send("MOVE $dx $dy")

    override fun onTap() = send("CLICK")

    override fun onLongPress() = send("LONG")

    override fun onSwipe(dxPermille: Int, dyPermille: Int) = send("SWIPE $dxPermille $dyPermille")

    override fun onTwoFingerTap() = send("BACK")
}

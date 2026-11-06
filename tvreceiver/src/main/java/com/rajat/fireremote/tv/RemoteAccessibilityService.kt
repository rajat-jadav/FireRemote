package com.rajat.fireremote.tv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.hypot

/**
 * Does the real work on the TV:
 *  - draws a pointer overlay and injects taps / swipes with dispatchGesture()
 *  - moves focus around the screen (D-pad emulation) through accessibility nodes
 *  - Back / Home / Recents via global actions
 *  - volume through AudioManager
 *  - launches apps
 * The CommandServer calls the public functions from network threads; every
 * function posts onto the main thread before touching anything.
 */
class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FireRemote"
        const val PORT = 8765
        private const val CURSOR_HIDE_DELAY_MS = 6000L

        @Volatile
        var instance: RemoteAccessibilityService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var audio: AudioManager

    private var cursorView: CursorView? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var server: CommandServer? = null

    private var screenW = 1920
    private var screenH = 1080
    private var cx = 960f
    private var cy = 540f

    private val hideCursor = Runnable { cursorView?.visibility = View.INVISIBLE }

    // ------------------------------------------------------------ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        readScreenSize()
        cx = screenW / 2f
        cy = screenH / 2f
        addCursor()
        if (server == null) {
            server = CommandServer(this).also { it.start() }
        }
        Log.i(TAG, "Service connected, screen ${screenW}x$screenH")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed: we only act on commands from the phone.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        server?.stop()
        server = null
        main.removeCallbacks(hideCursor)
        cursorView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        cursorView = null
        cursorParams = null
        if (instance === this) instance = null
    }

    // --------------------------------------------------------------- cursor

    @Suppress("DEPRECATION")
    private fun readScreenSize() {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
    }

    private fun addCursor() {
        if (cursorView != null) return
        val size = (36 * resources.displayMetrics.density).toInt()
        val view = CursorView(this).apply { visibility = View.INVISIBLE }
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cx.toInt()
            y = cy.toInt()
        }
        windowManager.addView(view, lp)
        cursorView = view
        cursorParams = lp
    }

    private fun updateCursor() {
        val view = cursorView ?: return
        val lp = cursorParams ?: return
        lp.x = cx.toInt()
        lp.y = cy.toInt()
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Cursor view is gone: ${e.message}")
        }
        view.visibility = View.VISIBLE
        main.removeCallbacks(hideCursor)
        main.postDelayed(hideCursor, CURSOR_HIDE_DELAY_MS)
    }

    // ------------------------------------------------- 1) mouse and pointer

    fun moveCursor(dx: Int, dy: Int) {
        main.post {
            cx = (cx + dx).coerceIn(0f, screenW - 1f)
            cy = (cy + dy).coerceIn(0f, screenH - 1f)
            updateCursor()
        }
    }

    fun click() {
        main.post {
            updateCursor()
            tap(cx, cy, 60)
        }
    }

    fun longPress() {
        main.post {
            updateCursor()
            tap(cx, cy, 800)
        }
    }

    /** Swipe centred on the pointer. Distances are in 1/1000 of the screen size. */
    fun swipe(dxPermille: Int, dyPermille: Int) {
        main.post {
            val dx = dxPermille / 1000f * screenW
            val dy = dyPermille / 1000f * screenH
            if (hypot(dx, dy) < 20f) return@post
            updateCursor()
            val path = Path().apply {
                moveTo(
                    (cx - dx / 2f).coerceIn(0f, screenW - 1f),
                    (cy - dy / 2f).coerceIn(0f, screenH - 1f)
                )
                lineTo(
                    (cx + dx / 2f).coerceIn(0f, screenW - 1f),
                    (cy + dy / 2f).coerceIn(0f, screenH - 1f)
                )
            }
            dispatch(GestureDescription.StrokeDescription(path, 0, 300))
        }
    }

    private fun tap(x: Float, y: Float, durationMs: Long) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(GestureDescription.StrokeDescription(path, 0, durationMs))
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        if (!dispatchGesture(gesture, null, null)) {
            Log.w(TAG, "Gesture was rejected by the system")
        }
    }

    // ------------------------------------------------------- 3) move pad

    fun dpad(direction: String) {
        main.post { handleDpad(direction) }
    }

    private fun handleDpad(direction: String) {
        val root = rootInActiveWindow ?: return
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

        if (direction == "OK") {
            if (focused != null) clickNodeOrParent(focused)
            return
        }

        val searchDir = when (direction) {
            "UP" -> View.FOCUS_UP
            "DOWN" -> View.FOCUS_DOWN
            "LEFT" -> View.FOCUS_LEFT
            "RIGHT" -> View.FOCUS_RIGHT
            else -> return
        }

        if (focused == null) {
            // Nothing focused yet: land on the first focusable thing on screen.
            firstFocusable(root)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            return
        }
        focused.focusSearch(searchDir)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    private fun firstFocusable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocusable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            firstFocusable(child)?.let { return it }
        }
        return null
    }

    private fun clickNodeOrParent(start: AccessibilityNodeInfo) {
        var node: AccessibilityNodeInfo? = start
        var depth = 0
        while (node != null && depth < 6) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
            depth++
        }
        // Fallback: tap the middle of the focused element.
        val bounds = Rect()
        start.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) tap(bounds.exactCenterX(), bounds.exactCenterY(), 60)
    }

    fun back() {
        main.post { performGlobalAction(GLOBAL_ACTION_BACK) }
    }

    fun home() {
        main.post { performGlobalAction(GLOBAL_ACTION_HOME) }
    }

    fun recents() {
        main.post { performGlobalAction(GLOBAL_ACTION_RECENTS) }
    }

    // ------------------------------------------------------ 4) volume

    fun volume(action: String) {
        main.post {
            val adjust = when (action) {
                "UP" -> AudioManager.ADJUST_RAISE
                "DOWN" -> AudioManager.ADJUST_LOWER
                "MUTE" -> AudioManager.ADJUST_TOGGLE_MUTE
                else -> return@post
            }
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjust, AudioManager.FLAG_SHOW_UI)
        }
    }

    // ------------------------------------------------ 2) quick start apps

    /** Installed apps that have a launcher entry, as (package, label), sorted by label. */
    fun listApps(): List<Pair<String, String>> {
        val pm = packageManager
        val found = LinkedHashMap<String, String>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == packageName) continue
                if (!found.containsKey(pkg)) found[pkg] = info.loadLabel(pm).toString()
            }
        }
        return found.map { it.key to it.value }.sortedBy { it.second.lowercase() }
    }

    fun launchApp(pkg: String) {
        main.post {
            val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
                ?: packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                Log.w(TAG, "No launch intent for $pkg")
                return@post
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Could not launch $pkg: ${e.message}")
            }
        }
    }
}

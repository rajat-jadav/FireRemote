package com.rajat.fireremote.tv

import android.content.Context
import java.security.SecureRandom
import java.util.Locale

/** Stores the pairing PIN the phone must send before it may control the TV. */
object Prefs {
    private const val FILE = "fireremote"
    private const val KEY_PIN = "pin"

    fun pin(ctx: Context): String {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return sp.getString(KEY_PIN, null) ?: newPin(ctx)
    }

    fun newPin(ctx: Context): String {
        val pin = String.format(Locale.US, "%06d", SecureRandom().nextInt(1_000_000))
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PIN, pin)
            .apply()
        return pin
    }
}

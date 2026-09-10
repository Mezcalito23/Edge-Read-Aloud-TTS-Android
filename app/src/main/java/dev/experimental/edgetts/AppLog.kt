package dev.experimental.edgetts

import android.util.Log

/** Log.d/i solo en debug: no construye la cadena en release. */
internal object AppLog {
    inline fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message())
    }

    inline fun i(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.i(tag, message())
    }
}

package io.github.zenith

import android.app.Application
import io.github.zenith.data.Paths

/** Roda em cada processo do app (o do launcher e o `:game`). */
class ZenithApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Paths.init(this)
    }
}

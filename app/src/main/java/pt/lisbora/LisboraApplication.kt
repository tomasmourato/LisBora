package pt.lisbora

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.maplibre.android.MapLibre

@HiltAndroidApp
class LisboraApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Must run before any MapView is created. No API key: tiles, glyphs and sprites are all local.
        MapLibre.getInstance(this)
    }
}

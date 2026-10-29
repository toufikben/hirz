package dz.hirz.app

import android.app.Application

class HirzApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: HirzApplication
            private set
    }
}

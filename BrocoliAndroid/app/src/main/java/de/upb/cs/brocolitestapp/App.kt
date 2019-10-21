package de.upb.cs.brocolitestapp

import android.app.Application

class MyApp : Application() {
    companion object {
        lateinit var instance: MyApp
            private set
    }

    init {
        instance = this
    }
}

package app.lecturenotes

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Meta.init(this)
        Processor.start(this)
    }
}

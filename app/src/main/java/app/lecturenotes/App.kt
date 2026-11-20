package app.lecturenotes

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Meta.init(this)
        Usage.init(this)
        Power.init(this)
        Scripture.init(this)
        Processor.start(this)
    }
}

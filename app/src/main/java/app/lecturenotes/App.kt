package app.lecturenotes

import android.app.Application

class App : Application() {
    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Scripture.init(this) // 聖句の表（保存済みの文を直すときに使うので先に）
        Repo.init(this)
        Meta.init(this)
        Usage.init(this)
        Power.init(this)
        Processor.start(this)
    }
}

package app.lecturenotes

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Scripture.init(this) // 聖句の表（保存済みの文を直すときに使うので先に）
        Repo.init(this)
        Meta.init(this)
        Usage.init(this)
        Power.init(this)
        Processor.start(this)
    }
}

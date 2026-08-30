package dev.junyan.scenariotimer

import android.app.Application
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val log = buildString {
                append("Time: ${df.format(Date())}\n")
                append("Thread: ${thread.name}\n")
                append("Error: ${throwable.message}\n")
                append("Stack:\n")
                throwable.stackTraceToString().lines().take(15).forEach { append("$it\n") }
            }
            File(filesDir, "crash.log").writeText(log)
            Process.killProcess(Process.myPid())
        }
    }
}

package dev.junyan.scenariotimer

data class TimerEntry(
    val id: Int,
    val sceneIndex: Int,
    val sceneName: String,
    val totalSeconds: Long,
    var remainingSeconds: Long,
    var endTimeMillis: Long,
    var isRunning: Boolean = false,
    var isPaused: Boolean = false,
    var isFinished: Boolean = false,
    val finishAction: String = "mute",
)

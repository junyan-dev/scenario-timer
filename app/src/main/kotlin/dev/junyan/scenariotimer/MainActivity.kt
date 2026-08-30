package dev.junyan.scenariotimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.Toast
import kotlin.math.abs
import androidx.appcompat.app.AppCompatActivity
import dev.junyan.scenariotimer.databinding.ActivityMainBinding
import com.google.android.material.chip.Chip
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TimerScene(val name: String, val durationMinutes: Int, val finishAction: String = "mute", val durationUnit: String = "minutes")

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var scenes = mutableListOf<TimerScene>()
    private var selectedSceneIndex: Int = 0
    private val sceneTimerIds = mutableMapOf<Int, Int>()
    // 保存每行的视图引用
    private data class SceneRowViews(
        val durationText: android.widget.TextView,
        val statusText: android.widget.TextView,
        val pauseBtn: android.widget.ImageButton,
        val cancelBtn: android.widget.ImageButton,
    )
    private val rowViews = mutableMapOf<Int, SceneRowViews>()

    private val scenePalette = intArrayOf(
        Color.parseColor("#007AFF"),
        Color.parseColor("#30B0C7"),
        Color.parseColor("#5E5CE6"),
        Color.parseColor("#34C759"),
        Color.parseColor("#FF9500"),
        Color.parseColor("#FF375F"),
    )

    private fun sceneColor(index: Int): Int = scenePalette[index % scenePalette.size]

    private fun tintBackground(color: Int, alpha: Float): Int {
        val r = (Color.red(color) * alpha + 255 * (1 - alpha)).toInt()
        val g = (Color.green(color) * alpha + 255 * (1 - alpha)).toInt()
        val b = (Color.blue(color) * alpha + 255 * (1 - alpha)).toInt()
        return Color.argb(255, r, g, b)
    }

    private val timerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val timerId = intent.getIntExtra(TimerService.EXTRA_TIMER_ID, -1)
            val sceneIndex = intent.getIntExtra(TimerService.EXTRA_SCENE_INDEX, -1)
            val state = intent.getStringExtra(TimerService.EXTRA_STATE)
            if (sceneIndex < 0) return

            sceneTimerIds[sceneIndex] = timerId

            when (intent.getStringExtra(TimerService.EXTRA_STATE)) {
                TimerService.STATE_TICK -> {
                    val remaining = intent.getLongExtra(TimerService.EXTRA_REMAINING, 0)
                    updateSceneRowTimer(sceneIndex, TimerService.formatTime(remaining), isPaused = false)
                    if (sceneIndex == selectedSceneIndex) {
                        updateTopDisplay()
                    }
                }
                TimerService.STATE_PAUSED -> {
                    val entry = TimerService.timers[timerId]
                    updateSceneRowTimer(sceneIndex, TimerService.formatTime(entry?.remainingSeconds ?: 0), isPaused = true)
                    if (sceneIndex == selectedSceneIndex) {
                        updateTopDisplay()
                    }
                }
                TimerService.STATE_RESUMED -> {
                    val entry = TimerService.timers[timerId]
                    updateSceneRowTimer(sceneIndex, TimerService.formatTime(entry?.remainingSeconds ?: 0), isPaused = false)
                    if (sceneIndex == selectedSceneIndex) {
                        updateTopDisplay()
                    }
                }
                TimerService.STATE_FINISHED -> {
                    sceneTimerIds.remove(sceneIndex)
                    clearSceneRowTimer(sceneIndex)
                    if (sceneIndex == selectedSceneIndex) {
                        updateTopDisplay()
                    }
                    val entry = TimerService.timers[timerId]
                    if (entry?.finishAction == "ringtone") {
                        showStopRingtoneDialog()
                    }
                }
                TimerService.STATE_CANCELLED -> {
                    sceneTimerIds.remove(sceneIndex)
                    clearSceneRowTimer(sceneIndex)
                    if (sceneIndex == selectedSceneIndex) {
                        updateTopDisplay()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadScenes()
        renderScenes()
        setupAddSceneButton()
        updateTimerDisplay()
    }

    override fun onResume() {
        super.onResume()
        showCrashLogIfExists()
        val filter = IntentFilter(TimerService.BROADCAST_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(timerReceiver, filter)
        }
        syncWithService()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(timerReceiver)
    }

    private var ringtoneDialog: BottomSheetDialog? = null

    private fun showStopRingtoneDialog() {
        ringtoneDialog?.dismiss()
        val view = layoutInflater.inflate(R.layout.dialog_ringtone, null)
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnStopRingtone).setOnClickListener {
            startService(Intent(this, TimerService::class.java).apply {
                action = TimerService.ACTION_STOP_RINGTONE
            })
            ringtoneDialog?.dismiss()
            ringtoneDialog = null
        }
        ringtoneDialog = BottomSheetDialog(this).apply {
            setContentView(view)
            setCancelable(false)
            behavior.isDraggable = false
            show()
        }
    }

    private fun showCrashLogIfExists() {
        val file = File(filesDir, "crash.log")
        if (file.exists()) {
            val log = file.readText()
            file.delete()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("上次崩溃日志")
                .setMessage(log)
                .setPositiveButton("确定", null)
                .show()
        }
    }

    // ── Scene Row Timer State ────────────────────────────

    private fun updateSceneRowTimer(sceneIndex: Int, remainingText: String, isPaused: Boolean) {
        val rv = rowViews[sceneIndex] ?: return

        rv.durationText.text = remainingText
        rv.durationText.setTextColor(if (isPaused) getColor(R.color.label_secondary) else getColor(R.color.system_green))
        rv.statusText.visibility = View.VISIBLE
        rv.statusText.text = if (isPaused) "已暂停" else "运行中"
        rv.pauseBtn.visibility = View.VISIBLE
        rv.pauseBtn.setImageResource(if (isPaused) R.drawable.ic_play else R.drawable.ic_pause)
        rv.cancelBtn.visibility = View.VISIBLE
    }

    private fun clearSceneRowTimer(sceneIndex: Int) {
        val rv = rowViews[sceneIndex] ?: return
        val scene = scenes.getOrNull(sceneIndex) ?: return

        rv.durationText.text = "${scene.durationMinutes}${if (scene.durationUnit == "seconds") "秒" else "分钟"}"
        rv.durationText.setTextColor(getColor(R.color.label_secondary))
        rv.statusText.visibility = View.GONE
        rv.pauseBtn.visibility = View.GONE
        rv.cancelBtn.visibility = View.GONE
    }

    private fun syncWithService() {
        sceneTimerIds.clear()
        TimerService.timers.values.forEach { entry ->
            if (entry.sceneIndex >= 0 && entry.sceneIndex < binding.sceneContainer.childCount) {
                sceneTimerIds[entry.sceneIndex] = entry.id
                updateSceneRowTimer(entry.sceneIndex, TimerService.formatTime(entry.remainingSeconds), entry.isPaused)
            }
        }
        for (i in 0 until binding.sceneContainer.childCount) {
            if (i !in sceneTimerIds) clearSceneRowTimer(i)
        }
    }

    // ── Scene Management ─────────────────────────────────

    private fun loadScenes() {
        val prefs = getSharedPreferences("sleep_timer", MODE_PRIVATE)
        val json = prefs.getString("scenes", null)
        if (json != null) {
            try {
                val arr = JSONArray(json)
                scenes.clear()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    scenes.add(TimerScene(
                        obj.getString("name"),
                        obj.getInt("durationMinutes"),
                        obj.optString("finishAction", "mute"),
                        obj.optString("durationUnit", "minutes")
                    ))
                }
            } catch (_: Exception) {
                scenes.clear()
            }
        }
        if (scenes.isEmpty()) {
            scenes = getDefaultScenes().toMutableList()
            saveScenes()
        }
        selectedSceneIndex = prefs.getInt("selectedScene", 0).coerceIn(0, scenes.size - 1)
    }

    private fun saveScenes() {
        val arr = JSONArray()
        scenes.forEach { scene ->
            arr.put(JSONObject().apply {
                put("name", scene.name)
                put("durationMinutes", scene.durationMinutes)
                put("finishAction", scene.finishAction)
                put("durationUnit", scene.durationUnit)
            })
        }
        getSharedPreferences("sleep_timer", MODE_PRIVATE).edit()
            .putString("scenes", arr.toString())
            .putInt("selectedScene", selectedSceneIndex)
            .commit()
    }

    private fun getDefaultScenes(): List<TimerScene> = listOf(
        TimerScene("睡前", 30, "mute", "minutes"),
        TimerScene("冥想", 10, "mute", "minutes"),
        TimerScene("午休", 20, "mute", "minutes"),
    )

    private var openSwipeIndex: Int = -1

    private fun renderScenes() {
        binding.sceneContainer.removeAllViews()
        rowViews.clear()
        openSwipeIndex = -1
        val inflater = LayoutInflater.from(this)

        scenes.forEachIndexed { index, scene ->
            val view = inflater.inflate(R.layout.item_scene, binding.sceneContainer, false)
            val foreground = view.findViewById<android.view.View>(R.id.swipeForeground)
            val startBtn = view.findViewById<android.view.View>(R.id.btnSceneStart)
            val editBtn = view.findViewById<android.view.View>(R.id.btnSceneEdit)
            val deleteBtn = view.findViewById<android.view.View>(R.id.btnSceneDelete)
            val nameText = view.findViewById<android.widget.TextView>(R.id.tvItemSceneName)
            val durationText = view.findViewById<android.widget.TextView>(R.id.tvItemSceneDuration)
            val statusText = view.findViewById<android.widget.TextView>(R.id.tvSceneTimerStatus)
            val pauseBtn = view.findViewById<android.widget.ImageButton>(R.id.btnScenePause)
            val cancelBtn = view.findViewById<android.widget.ImageButton>(R.id.btnSceneCancel)
            val colorBar = view.findViewById<android.view.View>(R.id.colorBar)

            rowViews[index] = SceneRowViews(durationText, statusText, pauseBtn, cancelBtn)

            nameText.text = scene.name
            durationText.text = "${scene.durationMinutes}${if (scene.durationUnit == "seconds") "秒" else "分钟"}"

            val color = sceneColor(index)
            val isSelected = index == selectedSceneIndex

            colorBar.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            foreground.setBackgroundColor(
                if (isSelected) tintBackground(color, 0.1f) else getColor(R.color.card_background)
            )
            nameText.setTextColor(if (isSelected) color else getColor(R.color.label_primary))
            durationText.setTextColor(
                if (isSelected) color else getColor(R.color.label_secondary)
            )

            startBtn.setOnClickListener { v ->
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                closeAllSwipes()
                selectedSceneIndex = index
                saveScenes()
                renderScenes()
                updateTimerDisplay()
                startTimerForScene(index)
            }
            editBtn.setOnClickListener {
                closeAllSwipes()
                showEditSceneDialog(index)
            }
            deleteBtn.setOnClickListener {
                closeAllSwipes()
                deleteScene(index)
            }

            // 卡片上的暂停/取消按钮（控制该卡片自己的计时器）
            pauseBtn.setOnClickListener { v ->
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                val timerId = sceneTimerIds[index] ?: return@setOnClickListener
                val entry = TimerService.timers[timerId] ?: return@setOnClickListener
                val act = if (entry.isPaused) TimerService.ACTION_RESUME else TimerService.ACTION_PAUSE
                startService(Intent(this, TimerService::class.java).apply {
                    action = act
                    putExtra(TimerService.EXTRA_TIMER_ID, timerId)
                })
            }
            cancelBtn.setOnClickListener { v ->
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                val timerId = sceneTimerIds[index] ?: return@setOnClickListener
                startService(Intent(this, TimerService::class.java).apply {
                    action = TimerService.ACTION_CANCEL
                    putExtra(TimerService.EXTRA_TIMER_ID, timerId)
                })
            }

            attachSwipe(view, foreground, index)
            binding.sceneContainer.addView(view)
        }

        // 恢复计时器状态
        syncWithService()
    }

    private fun closeAllSwipes() {
        for (i in 0 until binding.sceneContainer.childCount) {
            val fg = binding.sceneContainer.getChildAt(i)
                .findViewById<android.view.View?>(R.id.swipeForeground) ?: continue
            fg.animate().translationX(0f).setDuration(150).start()
        }
        openSwipeIndex = -1
    }

    private fun attachSwipe(root: android.view.View, foreground: android.view.View, index: Int) {
        val touchSlop = ViewConfiguration.get(root.context).scaledTouchSlop
        val density = root.resources.displayMetrics.density
        val maxSwipe = 164f * density
        var startX = 0f
        var startY = 0f
        var initialTx = 0f
        var moved = false

        foreground.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX
                    startY = ev.rawY
                    initialTx = foreground.translationX
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (index in sceneTimerIds) return@setOnTouchListener true  // 计时中禁用左滑
                    val dx = ev.rawX - startX
                    val dy = ev.rawY - startY
                    if (!moved && abs(dx) < touchSlop && abs(dy) < touchSlop) return@setOnTouchListener false
                    if (!moved) {
                        moved = true
                        if (openSwipeIndex != -1 && openSwipeIndex != index) closeAllSwipes()
                    }
                    val newTx = (initialTx + dx).coerceIn(-maxSwipe, 0f)
                    foreground.translationX = newTx
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (openSwipeIndex == index) {
                            closeAllSwipes()
                        } else {
                            closeAllSwipes()
                            foreground.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            selectedSceneIndex = index
                            saveScenes()
                            renderScenes()
                            updateTimerDisplay()
                        }
                    } else {
                        val tx = foreground.translationX
                        if (tx < -maxSwipe / 2) {
                            // 左滑过半 → 展开编辑/删除
                            foreground.animate().translationX(-maxSwipe).setDuration(150).start()
                            openSwipeIndex = index
                        } else {
                            foreground.animate().translationX(0f).setDuration(150).start()
                            openSwipeIndex = -1
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        foreground.animate().translationX(0f).setDuration(150).start()
                        openSwipeIndex = -1
                    }
                }
            }
            true
        }
    }

    private fun showEditSceneDialog(editIndex: Int? = null) {
        val scene = editIndex?.let { scenes[it] }
        val dialogView = layoutInflater.inflate(R.layout.dialog_scene_edit, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.inputSceneName)
        val durationInput = dialogView.findViewById<EditText>(R.id.inputSceneDuration)
        val chipMute = dialogView.findViewById<Chip>(R.id.chipMute)
        val chipRingtone = dialogView.findViewById<Chip>(R.id.chipRingtone)
        val chipUnitMinutes = dialogView.findViewById<Chip>(R.id.chipUnitMinutes)
        val chipUnitSeconds = dialogView.findViewById<Chip>(R.id.chipUnitSeconds)

        if (scene != null) {
            nameInput.setText(scene.name)
            durationInput.setText(scene.durationMinutes.toString())
            if (scene.finishAction == "ringtone") chipRingtone.isChecked = true
            else chipMute.isChecked = true
            if (scene.durationUnit == "seconds") chipUnitSeconds.isChecked = true
            else chipUnitMinutes.isChecked = true
        } else {
            chipMute.isChecked = true
            chipUnitMinutes.isChecked = true
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (scene != null) "修改" else "添加")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                val name = nameInput.text.toString().trim()
                val duration = durationInput.text.toString().toIntOrNull() ?: 0
                if (name.isEmpty() || duration <= 0) {
                    Toast.makeText(this, "请填写完整信息", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val finishAction = if (chipRingtone.isChecked) "ringtone" else "mute"
                val durationUnit = if (chipUnitSeconds.isChecked) "seconds" else "minutes"
                if (editIndex != null) {
                    scenes[editIndex] = TimerScene(name, duration, finishAction, durationUnit)
                } else {
                    scenes.add(TimerScene(name, duration, finishAction, durationUnit))
                }
                saveScenes()
                renderScenes()
                updateTimerDisplay()
            }
            .setNegativeButton("取消", null)
            .show()
            .apply {
                window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            }
    }

    private fun deleteScene(index: Int) {
        if (scenes.size <= 1) {
            Toast.makeText(this, "至少保留一个场景", Toast.LENGTH_SHORT).show()
            return
        }
        scenes.removeAt(index)
        when {
            selectedSceneIndex >= scenes.size -> selectedSceneIndex = scenes.size - 1
            selectedSceneIndex > index -> selectedSceneIndex--
            selectedSceneIndex == index -> selectedSceneIndex = 0
        }
        saveScenes()
        renderScenes()
        updateTimerDisplay()
    }

    // ── Timer ────────────────────────────────────────────

    private fun setupAddSceneButton() {
        binding.btnAddScene.setOnClickListener {
            showEditSceneDialog()
        }
    }

    private fun startTimerForScene(sceneIndex: Int) {
        val scene = scenes.getOrNull(sceneIndex) ?: return
        if (sceneIndex in sceneTimerIds) return  // 该场景已有计时器在运行
        val totalSeconds = if (scene.durationUnit == "seconds") scene.durationMinutes else scene.durationMinutes * 60
        if (totalSeconds <= 0) {
            Toast.makeText(this, "时长无效", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(this, TimerService::class.java).apply {
                action = TimerService.ACTION_START
                putExtra(TimerService.EXTRA_SECONDS, totalSeconds)
                putExtra(TimerService.EXTRA_FINISH_ACTION, scene.finishAction)
                putExtra(TimerService.EXTRA_SCENE_NAME, scene.name)
                putExtra(TimerService.EXTRA_SCENE_INDEX, sceneIndex)
            }
            startForegroundService(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            android.util.Log.e("SleepTimer", "startTimer failed", e)
        }
    }

    private fun updateTimerDisplay() {
        updateTopDisplay()
    }

    /** 顶部大时间显示：选中场景有计时器则显示剩余时间，否则显示原始时长 */
    private fun updateTopDisplay() {
        val scene = scenes.getOrNull(selectedSceneIndex) ?: return
        val color = sceneColor(selectedSceneIndex)
        binding.tvSelectedScene.text = scene.name
        binding.tvSelectedScene.setTextColor(color)

        val timerId = sceneTimerIds[selectedSceneIndex]
        val entry = timerId?.let { TimerService.timers[it] }
        if (entry != null && !entry.isFinished) {
            binding.tvTimerDisplay.text = TimerService.formatTime(entry.remainingSeconds)
        } else {
            val totalSeconds = if (scene.durationUnit == "seconds") scene.durationMinutes.toLong() else scene.durationMinutes * 60L
            binding.tvTimerDisplay.text = TimerService.formatTime(totalSeconds)
        }
    }
}

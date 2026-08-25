package dev.autopilot.terminal

import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.autopilot.terminal.ui.AppRoot

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashReporter.isSafeMode(this)) {
            showSafeMode()
        } else {
            setContent { AppRoot() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!CrashReporter.isSafeMode(this)) {
            CrashReporter.markLaunchHealthy(this)
        }
    }

    private fun showSafeMode() {
        val ctx = this
        val logView = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#F87171"))
            typeface = android.graphics.Typeface.MONOSPACE
            text = TextUtils.concat(
                "应用连续崩溃，已进入安全模式。\n以下为崩溃日志，请全选复制发给开发者：\n\n",
                runCatching { CrashReporter.readableSummary(ctx) }.getOrDefault("读取失败")
            )
            setTextIsSelectable(true)
        }
        val resetBtn = Button(ctx).apply {
            text = "重置并尝试正常启动"
            setOnClickListener {
                CrashReporter.clearSafeMode(ctx)
                recreate()
            }
        }
        val root = ScrollView(ctx).apply {
            setBackgroundColor(Color.parseColor("#0C0C14"))
            addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(32, 48, 32, 48)
                    addView(resetBtn)
                    addView(logView)
                }
            )
        }
        setContentView(root)
    }
}

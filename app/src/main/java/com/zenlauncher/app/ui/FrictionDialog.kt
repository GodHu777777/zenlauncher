package com.zenlauncher.app.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Window
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.zenlauncher.app.R

class FrictionDialog(
    context: Context,
    private val appName: String,
    private val totalSeconds: Int,
    private val promptText: String,
    private val onConfirmed: () -> Unit
) : Dialog(context) {

    private var countDownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_friction)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setCancelable(false)

        val tvTitle = findViewById<TextView>(R.id.tvFrictionTitle)
        val tvPrompt = findViewById<TextView>(R.id.tvFrictionPrompt)
        val tvCount = findViewById<TextView>(R.id.tvCountdownNumber)
        val pbCountdown = findViewById<ProgressBar>(R.id.pbCountdown)
        val btnGiveUp = findViewById<MaterialButton>(R.id.btnGiveUp)
        val btnContinue = findViewById<MaterialButton>(R.id.btnContinue)

        tvTitle.text = "即将打开「$appName」"
        tvPrompt.text = promptText
        tvCount.text = "$totalSeconds"

        btnGiveUp.setOnClickListener {
            dismiss()
        }

        btnContinue.isEnabled = false
        btnContinue.text = "冷静缓冲中 (${totalSeconds}s)"

        val totalMillis = totalSeconds * 1000L
        countDownTimer = object : CountDownTimer(totalMillis, 100L) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = ((millisUntilFinished + 999) / 1000).toInt()
                tvCount.text = "$secondsLeft"
                val progress = ((totalMillis - millisUntilFinished) * 100 / totalMillis).toInt()
                pbCountdown.progress = progress
                btnContinue.text = "冷静缓冲中 (${secondsLeft}s)"
            }

            override fun onFinish() {
                tvCount.text = "✓"
                pbCountdown.progress = 100
                btnContinue.isEnabled = true
                btnContinue.text = "继续打开"
                btnContinue.setOnClickListener {
                    dismiss()
                    onConfirmed()
                }
            }
        }.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        countDownTimer?.cancel()
    }
}

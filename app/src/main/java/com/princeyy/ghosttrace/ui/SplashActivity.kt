package com.princeyy.ghosttrace.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.princeyy.ghosttrace.databinding.ActivitySplashBinding
import com.princeyy.ghosttrace.utils.PermissionUtils

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!PermissionUtils.hasUsageAccess(this)) {
            // Show permission screen
            binding.tvStatus.text = "Usage access required"
            binding.tvSub.text = "Grant permission to scan deleted apps"
            binding.btnAction.text = "Grant Permission"
            binding.btnAction.setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        } else {
            binding.tvStatus.text = "Scanning ghosts..."
            binding.tvSub.text = "Analysing your usage log"
            binding.btnAction.text = "Open GhostTrace"
            binding.btnAction.setOnClickListener { launch() }
            Handler(Looper.getMainLooper()).postDelayed({ launch() }, 1800)
        }
    }

    override fun onResume() {
        super.onResume()
        if (PermissionUtils.hasUsageAccess(this)) {
            binding.tvStatus.text = "Scanning ghosts..."
            binding.tvSub.text = "Analysing your usage log"
            binding.btnAction.text = "Open GhostTrace"
            binding.btnAction.setOnClickListener { launch() }
        }
    }

    private fun launch() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

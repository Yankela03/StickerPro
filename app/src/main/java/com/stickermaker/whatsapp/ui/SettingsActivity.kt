package com.stickermaker.whatsapp.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.stickermaker.whatsapp.BuildConfig
import com.stickermaker.whatsapp.R
import com.stickermaker.whatsapp.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.tvVersion.text = getString(R.string.version_label, BuildConfig.VERSION_NAME)

        if (BuildConfig.PRIVACY_POLICY_URL.startsWith("https://")) {
            binding.btnPrivacyPolicy.visibility = View.VISIBLE
            binding.btnPrivacyPolicy.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL)))
            }
        }
    }
}

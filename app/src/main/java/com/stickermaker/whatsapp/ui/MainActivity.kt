package com.stickermaker.whatsapp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.stickermaker.whatsapp.R
import com.stickermaker.whatsapp.ads.AdManager
import com.stickermaker.whatsapp.analytics.AnalyticsManager
import com.stickermaker.whatsapp.databinding.ActivityMainBinding
import com.stickermaker.whatsapp.media.MediaValidator
import com.stickermaker.whatsapp.media.ValidationResult
import com.stickermaker.whatsapp.StickerPro.StickerPackRepository
import com.stickermaker.whatsapp.media.TikTokDownloadResult
import com.stickermaker.whatsapp.media.TikTokVideoDownloader
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleIncomingMedia(it, "image/*") }
    }
    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleIncomingMedia(it, "video/*") }
    }

    private var pendingPickIsVideo = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchPicker(pendingPickIsVideo)
        } else {
            Toast.makeText(this, R.string.error_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCreateSticker.setOnClickListener { showMediaTypeDialog() }
        binding.btnTikTokLink.setOnClickListener { showTikTokLinkDialog() }
        binding.btnCreatePack.setOnClickListener { showCreatePackDialog() }
        binding.btnViewStickers.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        AdManager.loadBanner(this, binding.adContainer)
        AdManager.preloadInterstitialAd(this)
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (!sharedText.isNullOrEmpty()) {
            val link = Regex("https://\\S+").find(sharedText)?.value?.trimEnd('.', ',', ')')
            if (link != null && TikTokVideoDownloader.isTikTokUrl(link)) {
                downloadTikTok(link)
                return
            }
        }
        val uri = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } ?: return
        handleIncomingMedia(uri, intent.type)
    }

    private fun showTikTokLinkDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "https://.../video/..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(48, 16, 48, 16)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.tiktok_dialog_title)
            .setMessage(R.string.tiktok_dialog_message)
            .setView(input)
            .setPositiveButton(R.string.tiktok_download, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val link = input.text.toString().trim()
                if (!TikTokVideoDownloader.isTikTokUrl(link)) {
                    input.error = getString(R.string.error_invalid_tiktok_link)
                } else {
                    dialog.dismiss()
                    downloadTikTok(link)
                }
            }
        }
        dialog.show()
    }

    private fun downloadTikTok(link: String) {
        val progress = AlertDialog.Builder(this)
            .setMessage(R.string.tiktok_downloading)
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch {
            when (val result = TikTokVideoDownloader.download(this@MainActivity, link)) {
                is TikTokDownloadResult.Success -> {
                    progress.dismiss()
                    processDownloadedTikTok(result.file)
                }
                is TikTokDownloadResult.Error -> {
                    progress.dismiss()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.tiktok_download_failed)
                        .setMessage(result.message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun processDownloadedTikTok(file: java.io.File) {
        val uri = Uri.fromFile(file)
        when (val validation = MediaValidator.validate(this, uri, "video/mp4")) {
            is ValidationResult.Success -> EditorActivity.start(
                this, videoUri = uri, imageUri = null, deleteVideoSourceAfterCopy = true
            )
            is ValidationResult.Failure -> {
                file.delete()
                Toast.makeText(this, validation.reasonResId, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCreatePackDialog() {
        val input = android.widget.EditText(this)
        input.hint = "Pack Name"
        AlertDialog.Builder(this)
            .setTitle("Create New Pack")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val id = StickerPackRepository.createNewPack(this, name)
                    StickerPackRepository.setActivePackIdentifier(this, id)
                    Toast.makeText(this, "Pack '$name' created and set as active", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMediaTypeDialog() {
        val options = arrayOf(getString(R.string.btn_choose_video), getString(R.string.btn_choose_image))
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_choose_media_type)
            .setItems(options) { _, which ->
                val isVideo = which == 0
                requestMediaAccess(isVideo)
            }
            .show()
    }

    private fun requestMediaAccess(isVideo: Boolean) {
        val activeId = StickerPackRepository.getActivePackIdentifier(this)
        val stickers = StickerPackRepository.loadVisibleStickers(this, activeId)
        if (stickers.size >= StickerPackRepository.MAX_STICKERS_PER_PACK) {
            Toast.makeText(this, "Pack is full (max ${StickerPackRepository.MAX_STICKERS_PER_PACK})", Toast.LENGTH_SHORT).show()
        } else {
            launchPickerFlow(isVideo)
        }
    }

    private fun launchPickerFlow(isVideo: Boolean) {
        pendingPickIsVideo = isVideo
        val permission = when {
            Build.VERSION.SDK_INT >= 33 && isVideo -> Manifest.permission.READ_MEDIA_VIDEO
            Build.VERSION.SDK_INT >= 33 -> Manifest.permission.READ_MEDIA_IMAGES
            Build.VERSION.SDK_INT >= 29 -> null
            else -> Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (permission == null || ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            launchPicker(isVideo)
        } else {
            permissionLauncher.launch(permission)
        }
    }

    private fun launchPicker(isVideo: Boolean) {
        if (isVideo) pickVideoLauncher.launch("video/*") else pickImageLauncher.launch("image/*")
    }

    private fun handleIncomingMedia(uri: Uri, mimeTypeHint: String?) {
        val activeId = StickerPackRepository.getActivePackIdentifier(this)
        val stickers = StickerPackRepository.loadVisibleStickers(this, activeId)
        if (stickers.size >= StickerPackRepository.MAX_STICKERS_PER_PACK) {
            Toast.makeText(this, "Pack is full (max ${StickerPackRepository.MAX_STICKERS_PER_PACK})", Toast.LENGTH_SHORT).show()
        } else {
            processIncomingMedia(uri, mimeTypeHint)
        }
    }

    private fun processIncomingMedia(uri: Uri, mimeTypeHint: String?) {
        when (val result = MediaValidator.validate(this, uri, mimeTypeHint)) {
            is ValidationResult.Success -> {
                when (val type = result.mediaType) {
                    is com.stickermaker.whatsapp.media.MediaType.Video -> {
                        AnalyticsManager.logVideoSelected(this)
                        EditorActivity.start(this, videoUri = type.uri, imageUri = null)
                    }
                    is com.stickermaker.whatsapp.media.MediaType.Image -> {
                        AnalyticsManager.logImageSelected(this)
                        EditorActivity.start(this, videoUri = null, imageUri = type.uri)
                    }
                }
            }
            is ValidationResult.Failure -> {
                Toast.makeText(this, result.reasonResId, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

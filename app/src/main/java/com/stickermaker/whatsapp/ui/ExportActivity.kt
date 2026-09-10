package com.stickermaker.whatsapp.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.stickermaker.whatsapp.R
import com.stickermaker.whatsapp.ads.AdManager
import com.stickermaker.whatsapp.analytics.AnalyticsManager
import com.stickermaker.whatsapp.databinding.ActivityExportBinding
import com.stickermaker.whatsapp.utils.FileUtils
import com.stickermaker.whatsapp.StickerPro.AddToWhatsAppResult
import com.stickermaker.whatsapp.StickerPro.StickerPackRepository
import com.stickermaker.whatsapp.StickerPro.WhatsAppStickerExporter
import java.io.File

class ExportActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_STICKER_PATH = "extra_sticker_path"
        private const val EXTRA_IS_ANIMATED = "extra_is_animated"

        fun start(context: Context, stickerFilePath: String, isAnimated: Boolean) {
            val intent = Intent(context, ExportActivity::class.java).apply {
                putExtra(EXTRA_STICKER_PATH, stickerFilePath)
                putExtra(EXTRA_IS_ANIMATED, isAnimated)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityExportBinding
    private lateinit var stickerFile: File
    private var isAnimated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_STICKER_PATH)
        isAnimated = intent.getBooleanExtra(EXTRA_IS_ANIMATED, false)
        if (path == null) {
            finish()
            return
        }
        stickerFile = File(path)

        // Use Glide to show the preview so animations play if it's an animated sticker.
        Glide.with(this)
            .load(stickerFile)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean = false
                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                    if (resource is Animatable) {
                        resource.start()
                    }
                    return false
                }
            })
            .into(binding.ivStickerPreview)
            
        binding.tvFileInfo.text = FileUtils.humanReadableSize(stickerFile.length())

        binding.btnAddToWhatsApp.text = getString(R.string.btn_watch_ad_to_save)
        binding.btnAddToWhatsApp.setOnClickListener { onAddToWhatsAppClicked() }
        binding.btnViewGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
            finish() // head straight to gallery
        }
    }

    private fun onAddToWhatsAppClicked() {
        setLoading(true)
        if (com.stickermaker.whatsapp.ads.AdManager.isRewardedAdReady()) {
            com.stickermaker.whatsapp.ads.AdManager.showRewardedAd(this) { earned ->
                if (earned) com.stickermaker.whatsapp.analytics.AnalyticsManager.logRewardedAdCompleted(this)
                finishExport()
            }
        } else {
            Toast.makeText(this, "Ad not ready, adding to WhatsApp directly...", Toast.LENGTH_SHORT).show()
            finishExport()
        }
    }

    private fun finishExport() {
        val activeId = StickerPackRepository.getActivePackIdentifier(this)
        StickerPackRepository.addStickerToPack(this, stickerFile, isAnimated, activeId)
        android.util.Log.d("ExportActivity", "Sticker added to pack, launching WhatsApp for $activeId")
        val result = WhatsAppStickerExporter.launchWhatsApp(this, activeId)
        setLoading(false)
        android.util.Log.d("ExportActivity", "WhatsApp launch result: $result")
        when (result) {
            is AddToWhatsAppResult.Launched -> {
                AnalyticsManager.logStickerExported(this)
                FileUtils.deleteQuietly(stickerFile) // temp working copy no longer needed
                // Show the success message as requested.
                Toast.makeText(this, "Sticker pack successfully added!", Toast.LENGTH_LONG).show()
                finish()
            }
            is AddToWhatsAppResult.WhatsAppNotInstalled -> {
                Toast.makeText(this, R.string.error_whatsapp_not_installed, Toast.LENGTH_LONG).show()
            }
            is AddToWhatsAppResult.Failed -> {
                Toast.makeText(this, "Export failed: ${result.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressExport.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnAddToWhatsApp.isEnabled = !loading
    }
}

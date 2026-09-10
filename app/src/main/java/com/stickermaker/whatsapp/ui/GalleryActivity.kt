package com.stickermaker.whatsapp.ui

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.stickermaker.whatsapp.R
import com.stickermaker.whatsapp.ads.AdManager
import com.stickermaker.whatsapp.analytics.AnalyticsManager
import com.stickermaker.whatsapp.databinding.ActivityGalleryBinding
import com.stickermaker.whatsapp.media.MediaValidator
import com.stickermaker.whatsapp.media.ValidationResult
import com.stickermaker.whatsapp.media.TikTokDownloadResult
import com.stickermaker.whatsapp.media.TikTokVideoDownloader
import com.stickermaker.whatsapp.StickerPro.AddToWhatsAppResult
import com.stickermaker.whatsapp.StickerPro.StickerPackRepository
import com.stickermaker.whatsapp.StickerPro.WhatsAppStickerExporter
import kotlinx.coroutines.launch

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private var selectedPackIdentifier: String? = null

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
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selectedPackIdentifier = intent.getStringExtra("pack_identifier")

        binding.toolbar.setNavigationOnClickListener { 
            if (selectedPackIdentifier != null) {
                selectedPackIdentifier = null
                loadData()
            } else {
                finish()
            }
        }
        binding.toolbar.inflateMenu(R.menu.menu_gallery)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.action_rename) {
                showRenameDialog()
                true
            } else false
        }
        
        // Use standard vertical list for packs, grid for stickers
        updateLayoutManager()

        binding.btnAddMore.setOnClickListener {
            showMediaTypeDialog()
        }
        binding.btnAddToWhatsApp.setOnClickListener { onAddToWhatsAppClicked() }
        binding.btnClearPack.setOnClickListener {
            val id = selectedPackIdentifier ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Clear Pack")
                .setMessage("Are you sure you want to delete all stickers in this pack?")
                .setPositiveButton("Clear") { _, _ ->
                    StickerPackRepository.clearPack(this, id)
                    loadData()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        AdManager.loadBanner(this, binding.adContainer)
        loadData()
    }

    private fun updateLayoutManager() {
        binding.recyclerStickers.layoutManager = if (selectedPackIdentifier == null) {
            androidx.recyclerview.widget.LinearLayoutManager(this)
        } else {
            GridLayoutManager(this, 3)
        }
    }

    private fun loadData() {
        updateLayoutManager()
        if (selectedPackIdentifier == null) {
            loadPacks()
        } else {
            loadStickers(selectedPackIdentifier!!)
        }
    }

    private fun loadPacks() {
        val packs = StickerPackRepository.loadAllPacks(this)
        binding.toolbar.title = "Sticker Packs"
        binding.tvEmpty.visibility = if (packs.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.tvEmpty.text = "No packs created yet"
        
        binding.recyclerStickers.adapter = PackAdapter(packs) { pack ->
            selectedPackIdentifier = pack.identifier
            updateLayoutManager()
            loadStickers(pack.identifier)
        }
        
        binding.btnAddMore.visibility = android.view.View.GONE
        binding.btnAddToWhatsApp.visibility = android.view.View.GONE
        binding.btnClearPack.visibility = android.view.View.GONE
        binding.toolbar.menu.findItem(R.id.action_rename)?.isVisible = false
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadStickers(identifier: String) {
        val pack = StickerPackRepository.loadPack(this, identifier)
        val stickers = pack?.stickers?.filterNot { it.imageFileName.contains("_pad") } ?: emptyList()
        val files = stickers.map { StickerPackRepository.stickerFile(this, identifier, it.imageFileName) }

        android.util.Log.d("GalleryActivity", "Loading stickers for $identifier. Pack: ${pack?.name}, Count: ${stickers.size}")
        files.forEach { android.util.Log.d("GalleryActivity", "Sticker file: ${it.absolutePath}, exists: ${it.exists()}") }

        binding.toolbar.title = pack?.name ?: getString(R.string.title_gallery)
        binding.tvEmpty.visibility = if (files.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.tvEmpty.text = getString(R.string.gallery_empty)
        
        binding.recyclerStickers.adapter = StickerAdapter(files) { file ->
            showPreviewDialog(file)
        }
        
        binding.btnAddMore.visibility = android.view.View.VISIBLE
        binding.btnAddToWhatsApp.visibility = android.view.View.VISIBLE
        binding.btnClearPack.visibility = android.view.View.VISIBLE
        binding.toolbar.menu.findItem(R.id.action_rename)?.isVisible = true
        
        binding.btnAddMore.isEnabled = stickers.size < StickerPackRepository.MAX_STICKERS_PER_PACK
        
        val canExport = stickers.size >= StickerPackRepository.MIN_STICKERS_PER_PACK
        binding.btnAddToWhatsApp.isEnabled = canExport
        if (canExport) {
            binding.btnAddToWhatsApp.text = getString(R.string.btn_add_to_whatsapp)
        } else {
            binding.btnAddToWhatsApp.text = getString(R.string.btn_add_to_whatsapp_min_3)
        }
        
        // Also set this pack as active so new stickers go here by default
        StickerPackRepository.setActivePackIdentifier(this, identifier)
    }

    private fun showMediaTypeDialog() {
        val options = arrayOf(
            getString(R.string.btn_choose_video),
            getString(R.string.btn_choose_image),
            getString(R.string.btn_paste_video_link)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_choose_media_type)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> requestMediaAccess(isVideo = true)
                    1 -> requestMediaAccess(isVideo = false)
                    2 -> showVideoLinkDialog()
                }
            }
            .show()
    }

    private fun showVideoLinkDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "https://.../video/..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
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
                    downloadOnlineVideo(link)
                }
            }
        }
        dialog.show()
    }

    private fun downloadOnlineVideo(link: String) {
        val progress = AlertDialog.Builder(this)
            .setMessage(R.string.tiktok_downloading)
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch {
            when (val result = TikTokVideoDownloader.download(this@GalleryActivity, link)) {
                is TikTokDownloadResult.Success -> {
                    progress.dismiss()
                    val uri = Uri.fromFile(result.file)
                    when (val validation = MediaValidator.validate(this@GalleryActivity, uri, "video/mp4")) {
                        is ValidationResult.Success -> EditorActivity.start(
                            this@GalleryActivity,
                            videoUri = uri,
                            imageUri = null,
                            deleteVideoSourceAfterCopy = true
                        )
                        is ValidationResult.Failure -> {
                            result.file.delete()
                            Toast.makeText(this@GalleryActivity, validation.reasonResId, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                is TikTokDownloadResult.Error -> {
                    progress.dismiss()
                    AlertDialog.Builder(this@GalleryActivity)
                        .setTitle(R.string.tiktok_download_failed)
                        .setMessage(result.message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun requestMediaAccess(isVideo: Boolean) {
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

    private fun showPreviewDialog(file: java.io.File) {
        val imageView = android.widget.ImageView(this)
        imageView.setPadding(48, 48, 48, 48)
        
        // Ensure the preview plays the animation by loading it with Glide
        Glide.with(this)
            .load(file)
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
            .into(imageView)
        
        AlertDialog.Builder(this)
            .setView(imageView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showRenameDialog() {
        val id = selectedPackIdentifier ?: return
        val pack = StickerPackRepository.loadPack(this, id) ?: return
        val input = android.widget.EditText(this)
        input.setText(pack.name)
        
        AlertDialog.Builder(this)
            .setTitle("Rename Sticker Pack")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    StickerPackRepository.setPackName(this, id, newName)
                    loadData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onAddToWhatsAppClicked() {
        val id = selectedPackIdentifier ?: return
        val pack = StickerPackRepository.loadPack(this, id)
        val stickers = pack?.stickers?.filterNot { it.imageFileName.contains("_pad") } ?: emptyList()
        
        if (stickers.size < StickerPackRepository.MIN_STICKERS_PER_PACK) {
            Toast.makeText(this, "WhatsApp requires at least ${StickerPackRepository.MIN_STICKERS_PER_PACK} stickers to add a pack. Create more!", Toast.LENGTH_LONG).show()
            return
        }

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
        val id = selectedPackIdentifier ?: return
        android.util.Log.d("GalleryActivity", "Finishing export, launching WhatsApp for $id")
        val result = WhatsAppStickerExporter.launchWhatsApp(this, id)
        setLoading(false)
        android.util.Log.d("GalleryActivity", "WhatsApp launch result: $result")
        when (result) {
            is AddToWhatsAppResult.Launched -> {
                AnalyticsManager.logStickerExported(this)
                // WhatsApp owns the confirmation UI; do not claim success before
                // the user confirms it there.
            }
            is AddToWhatsAppResult.WhatsAppNotInstalled -> {
                Toast.makeText(this, R.string.error_whatsapp_not_installed, Toast.LENGTH_LONG).show()
            }
            is AddToWhatsAppResult.Failed -> {
                Toast.makeText(this, "Export failed: ${result.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Kept for WhatsApp's sticker-pack activity result contract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != WhatsAppStickerExporter.ADD_PACK_REQUEST_CODE) return

        if (resultCode == RESULT_OK) {
            Toast.makeText(this, "Sticker pack added to WhatsApp.", Toast.LENGTH_LONG).show()
            return
        }

        val validationError = data?.getStringExtra("validation_error")
        val message = validationError?.takeIf { it.isNotBlank() }
            ?: "WhatsApp did not add the pack. Please update WhatsApp and try again."
        android.util.Log.e("GalleryActivity", "WhatsApp rejected sticker pack: $message")
        AlertDialog.Builder(this)
            .setTitle("Couldn’t add sticker pack")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun setLoading(loading: Boolean) {
        binding.progressGallery.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
        val id = selectedPackIdentifier
        val canExport = id != null && StickerPackRepository.loadVisibleStickers(this, id).size >= 3
        binding.btnAddToWhatsApp.isEnabled = !loading && canExport
    }
}

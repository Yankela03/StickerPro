package com.stickermaker.whatsapp.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.stickermaker.whatsapp.R
import com.stickermaker.whatsapp.ads.AdManager
import com.stickermaker.whatsapp.analytics.AnalyticsManager
import com.stickermaker.whatsapp.databinding.ActivityEditorBinding
import com.stickermaker.whatsapp.media.BitmapUtils
import com.stickermaker.whatsapp.media.FrameExtractor
import com.stickermaker.whatsapp.sticker.ConversionOutcome
import com.stickermaker.whatsapp.sticker.StickerConverter
import com.stickermaker.whatsapp.sticker.StickerSpec
import com.stickermaker.whatsapp.utils.FileUtils
import com.stickermaker.whatsapp.StickerPro.StickerPackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lets the user preview their media, trim a video to a sticker-safe window, and
 * kick off the (background-threaded) conversion to a WebP sticker.
 */
class EditorActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_VIDEO_URI = "extra_video_uri"
        private const val EXTRA_IMAGE_URI = "extra_image_uri"
        private const val EXTRA_DELETE_VIDEO_SOURCE = "extra_delete_video_source"

        fun start(context: Context, videoUri: Uri?, imageUri: Uri?, deleteVideoSourceAfterCopy: Boolean = false) {
            val intent = Intent(context, EditorActivity::class.java).apply {
                videoUri?.let { putExtra(EXTRA_VIDEO_URI, it) }
                imageUri?.let { putExtra(EXTRA_IMAGE_URI, it) }
                putExtra(EXTRA_DELETE_VIDEO_SOURCE, deleteVideoSourceAfterCopy)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityEditorBinding
    private var player: ExoPlayer? = null
    private var localVideoFile: File? = null
    private var imageUri: Uri? = null
    private var isVideoMode = false

    private val playbackTicker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!p.isPlaying) {
                binding.root.postDelayed(this, 100)
                return
            }
            
            val start = binding.trimView.startMs
            val end = binding.trimView.endMs.coerceAtLeast(start + StickerSpec.MIN_TRIM_DURATION_MS)
            
            if (p.currentPosition >= end || p.currentPosition < start) {
                p.seekTo(start)
            }
            binding.trimView.playbackPositionMs = p.currentPosition
            binding.tvCurrentTime.text = getString(R.string.current_frame_time, formatTime(p.currentPosition))
            binding.root.postDelayed(this, 30) // slightly faster for smoother playhead
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val videoUri = intent.getParcelableExtraCompat(EXTRA_VIDEO_URI)
        val incomingImageUri = intent.getParcelableExtraCompat(EXTRA_IMAGE_URI)

        AdManager.loadBanner(this, binding.adContainer)
        AdManager.preloadRewardedAd(this) // pre-load so it's ready by the export screen

        when {
            videoUri != null -> setupVideoMode(videoUri)
            incomingImageUri != null -> setupImageMode(incomingImageUri)
            else -> {
                Toast.makeText(this, R.string.error_invalid_file, Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        binding.btnConvert.setOnClickListener { onConvertClicked() }
        binding.btnResetCrop.setOnClickListener { binding.cropOverlay.resetCrop() }
        binding.btnPlayPause.setOnClickListener {
            player?.let { it.playWhenReady = !it.playWhenReady }
        }
    }

    private fun setupImageMode(uri: Uri) {
        isVideoMode = false
        imageUri = uri
        binding.imagePreview.visibility = android.view.View.VISIBLE
        binding.imagePreview.setImageURI(uri)
        binding.tvTrimInstructions.visibility = android.view.View.GONE
        binding.trimView.visibility = android.view.View.GONE
        binding.cbStaticSticker.visibility = android.view.View.GONE
        binding.tvSelectionTime.visibility = android.view.View.GONE
        binding.tvCurrentTime.visibility = android.view.View.GONE
        binding.btnPlayPause.visibility = android.view.View.GONE

        binding.imagePreview.post {
            val d = binding.imagePreview.drawable
            if (d != null) {
                val contentRect = BitmapUtils.computeContentRect(
                    binding.imagePreview.width, binding.imagePreview.height,
                    d.intrinsicWidth, d.intrinsicHeight
                )
                binding.cropOverlay.setContentRect(contentRect)
            }
        }
    }

    private fun setupVideoMode(uri: Uri) {
        isVideoMode = true
        binding.playerView.visibility = android.view.View.VISIBLE
        binding.btnPlayPause.visibility = android.view.View.VISIBLE

        val tempFile = FileUtils.copyUriToTemp(this, uri, "mp4")
        if (intent.getBooleanExtra(EXTRA_DELETE_VIDEO_SOURCE, false)) {
            runCatching { if (uri.scheme == "file") File(requireNotNull(uri.path)).delete() }
        }
        if (tempFile == null) {
            Toast.makeText(this, R.string.error_invalid_file, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        localVideoFile = tempFile

        val exoPlayer = ExoPlayer.Builder(this).build()
        binding.playerView.player = exoPlayer
        exoPlayer.setMediaItem(MediaItem.fromUri(tempFile.toUri()))
        exoPlayer.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
        exoPlayer.volume = 0f
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        player = exoPlayer

        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    binding.playerView.post {
                        val contentRect = BitmapUtils.computeContentRect(
                            binding.playerView.width, binding.playerView.height,
                            videoSize.width, videoSize.height
                        )
                        binding.cropOverlay.setContentRect(contentRect)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                binding.btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                binding.btnPlayPause.animate().alpha(if (isPlaying) 0.35f else 1f).setDuration(150).start()
            }

            override fun onEvents(p: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                if (p.duration > 0 && binding.trimView.videoDurationMs == 0L) {
                    val duration = p.duration
                    binding.trimView.videoDurationMs = duration
                    updateTrimInstructions()
                    updateTimeLabels(binding.trimView.startMs, binding.trimView.endMs)
                    
                    lifecycleScope.launch {
                        val thumbs = withContext(Dispatchers.Default) {
                            val count = (duration / 1000).toInt().coerceIn(10, 50)
                            FrameExtractor.extractThumbnails(tempFile, count, 120) // fixed height for better extraction
                        }
                        binding.trimView.setThumbnails(thumbs)
                    }
                }
            }
        })

        binding.trimView.onRangeChanged = { start, end ->
            updateTrimInstructions()
            updateTimeLabels(start, end)
            player?.playWhenReady = false // Pause while searching
            player?.seekTo(start)
            binding.trimView.playbackPositionMs = start
        }
        
        binding.trimView.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP || event.action == android.view.MotionEvent.ACTION_CANCEL) {
                player?.playWhenReady = true // Resume playback when done dragging
            }
            false // allow the view to handle its own touch logic
        }

        updateTrimInstructions()
        updateTimeLabels(binding.trimView.startMs, binding.trimView.endMs)
        binding.root.post(playbackTicker)
    }

    private fun updateTrimInstructions() {
        val seconds = StickerSpec.MAX_ANIMATION_DURATION_MS / 1000.0
        binding.tvTrimInstructions.text = getString(R.string.trim_instructions, seconds)
    }

    private fun updateTimeLabels(startMs: Long, endMs: Long) {
        binding.tvSelectionTime.text = getString(
            R.string.selection_time_range,
            formatTime(startMs),
            formatTime(endMs)
        )
    }

    private fun formatTime(milliseconds: Long): String {
        val safeMs = milliseconds.coerceAtLeast(0L)
        val minutes = safeMs / 60_000
        val seconds = (safeMs % 60_000) / 1_000
        val tenths = (safeMs % 1_000) / 100
        return "%d:%02d.%d".format(minutes, seconds, tenths)
    }

    private fun onConvertClicked() {
        setLoading(true)
        lifecycleScope.launch {
            val cropRect = binding.cropOverlay.getNormalizedCropRect()
            val outcome = if (isVideoMode) {
                val file = localVideoFile ?: return@launch
                val start = binding.trimView.startMs
                val end = binding.trimView.endMs.coerceAtLeast(start + StickerSpec.MIN_TRIM_DURATION_MS)
                StickerConverter.convertVideo(
                    this@EditorActivity,
                    file,
                    start,
                    end,
                    asStatic = binding.cbStaticSticker.isChecked,
                    cropRect = cropRect,
                    removeBackground = binding.swRemoveBackground.isChecked
                )
            } else {
                val uri = imageUri ?: return@launch
                StickerConverter.convertImage(
                    this@EditorActivity,
                    uri,
                    cropRect = cropRect,
                    removeBackground = binding.swRemoveBackground.isChecked
                )
            }

            setLoading(false)
            when (outcome) {
                is ConversionOutcome.Success -> {
                    AnalyticsManager.logStickerCreated(
                        this@EditorActivity,
                        outcome.result.isAnimated,
                        outcome.result.durationMs
                    )
                    
                    val activeId = StickerPackRepository.getActivePackIdentifier(this@EditorActivity)
                    val existing = StickerPackRepository.loadPack(this@EditorActivity, activeId)
                    if (existing == null) {
                        // First sticker! Prompt for a pack name before saving.
                        showInitialNameDialog(outcome.result)
                    } else {
                        saveAndFinish(outcome.result)
                    }
                }
                is ConversionOutcome.Error -> {
                    AnalyticsManager.recordException(Exception(outcome.message))
                    Toast.makeText(this@EditorActivity, R.string.error_conversion_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.loadingScrim.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
        binding.progressOverlay.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
        binding.tvProcessing.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnConvert.isEnabled = !loading
    }

    private fun showInitialNameDialog(result: com.stickermaker.whatsapp.sticker.StickerResult) {
        val input = android.widget.EditText(this)
        input.hint = "My Awesome Pack"
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Name Your Sticker Pack")
            .setMessage("Give your new pack a name before we save your first sticker.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "My Stickers" }
                // Create the pack with this name by saving the first sticker
                saveAndFinish(result, name)
            }
            .show()
    }

    private fun saveAndFinish(result: com.stickermaker.whatsapp.sticker.StickerResult, name: String? = null) {
        Log.d("EditorActivity", "Saving sticker: ${result.file.absolutePath}, isAnimated: ${result.isAnimated}")
        
        // WhatsApp does NOT allow mixing static and animated stickers in the same pack.
        // We split them into two default packs to ensure reliability.
        val packIdentifier = if (result.isAnimated) "my_animated_stickers" else "my_static_stickers"
        val defaultName = if (result.isAnimated) "My Animated Stickers" else "My Stickers"
        
        if (name != null) {
            StickerPackRepository.setPackName(this, packIdentifier, name)
        }

        val savedFile = StickerPackRepository.addStickerToPack(
            this,
            result.file,
            result.isAnimated,
            packIdentifier
        )

        if (savedFile != null) {
            Log.d("EditorActivity", "Sticker saved to pack: $packIdentifier, file: ${savedFile.absolutePath}")
            
            Toast.makeText(this, "Sticker saved to $defaultName!", Toast.LENGTH_SHORT).show()

            // Show preview logic as per "Part 2: Local Saving & UI Preview"
            binding.playerView.visibility = android.view.View.GONE
            binding.imagePreview.visibility = android.view.View.VISIBLE
            
            Glide.with(this)
                .load(savedFile)
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
                .into(binding.imagePreview)

            // Delayed navigation to Gallery to allow user to see the "saved" result.
            binding.root.postDelayed({
                startActivity(Intent(this, GalleryActivity::class.java).apply {
                    putExtra("pack_identifier", packIdentifier)
                })
                finish()
            }, 1500)
        } else {
            Toast.makeText(this, "Pack is full (max ${StickerPackRepository.MAX_STICKERS_PER_PACK}).", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.root.removeCallbacks(playbackTicker)
        player?.release()
        player = null
        localVideoFile?.delete()
        localVideoFile = null
    }
}

private fun Intent.getParcelableExtraCompat(key: String): Uri? {
    return if (android.os.Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(key, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
}

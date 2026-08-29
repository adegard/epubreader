package com.example.epubtts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.epubtts.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread

data class Bookmark(
    val title: String,
    val uri: String,
    val chapter: Int,
    val block: Int
)

data class LibraryEntry(
    val title: String,
    val uri: String,
    val chapter: Int,
    val block: Int,
    val progress: Int,
    val timestamp: Long,
    val cover: String = ""
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tts: TtsManager

    private var chapters: List<EpubTextExtractor.Chapter> = emptyList()
    private var blocks: List<String> = emptyList()
    private var chapterIndex = 0
    private var blockIndex = 0
    private var currentUri = ""
    private var currentTitle = ""
    private var currentCover = ""

    private var textSize = 16f
    private var ttsSpeed = 1.0f
    private var themeMode = 0
    private var showTitle = true
    private var showProgress = true
    private var ttsMode = "auto"
    private var hideBottomBars = false

    private var totalBlocks = 0
    private var totalChapters = 0

    private val themeNames = listOf("Day", "Night", "OLED black")
    private val defaultButtonBg = HashMap<Int, Drawable>()
    private val defaultButtonTint = HashMap<Int, ColorStateList?>()
    private val defaultButtonText = HashMap<Int, ColorStateList>()

    private var ttsActive = false
    private var ttsOnline = false
    private var ttsBusy = false
    private var lastOnlineAdvanceAt = 0L
    private var cachedLang: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentChunkOffsets = IntArray(0)
    private var lastUtteranceId = ""
    private var headerLength = 0
    private var currentSpannable: SpannableString? = null
    private var currentChunks: List<String> = emptyList()

    private lateinit var gestureDetector: GestureDetector

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri: Uri? = result.data?.data
                if (uri != null) loadEpub(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadPrefs()

        tts = TtsManager(this)
        tts.ttsMode = if (ttsMode == "online") "online" else "auto"
        tts.setSpeed(ttsSpeed)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                runOnUiThread { onUtteranceDone(utteranceId) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread { onUtteranceDone(utteranceId) }
            }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                runOnUiThread { highlightTtsRange(utteranceId, start, end) }
            }
        })
        tts.onSentenceHighlight = { s, e -> runOnUiThread { highlightSentence(s, e) } }
        tts.onOnlineUtteranceDone = {
            runOnUiThread {
                ttsBusy = false
                val now = System.currentTimeMillis()
                if (now - lastOnlineAdvanceAt < 250) return@runOnUiThread
                lastOnlineAdvanceAt = now
                nextBlockForTts()
            }
        }
        tts.onOnlineError = {
            runOnUiThread {
                ttsBusy = false
                val err = tts.lastOnlineError ?: "network error"
                toast("TTS failed: $err")
                finishReading()
            }
        }

        binding.btnOpenEpub.setOnClickListener { openEpubPicker() }
        binding.btnMenu.setOnClickListener { toggleMenu() }
        binding.btnPlay.setOnClickListener { toggleTts() }
        binding.btnPause.setOnClickListener { tts.pause(); ttsActive = false; binding.btnPlay.text = "Play" }
        binding.btnStop.setOnClickListener { stopTts() }
        binding.btnPrev.setOnClickListener { prevBlock() }
        binding.btnNext.setOnClickListener { nextBlock() }
        binding.btnSelectVoice.setOnClickListener { showVoiceSelector() }
        binding.btnSpeed.setOnClickListener { showSpeedDialog() }
        binding.btnTextSmall.setOnClickListener { changeTextSize(-2f) }
        binding.btnTextBig.setOnClickListener { changeTextSize(2f) }
        binding.btnBookmark.setOnClickListener { saveBookmark() }
        binding.btnBookmarks.setOnClickListener { showBookmarksDialog() }
        binding.btnTheme.setOnClickListener { toggleTheme() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
        binding.btnRecents.setOnClickListener { showLibraryDialog() }

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 80
            private val SWIPE_VELOCITY_THRESHOLD = 80
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (Math.abs(dx) > Math.abs(dy) * 1.5f && Math.abs(dx) > SWIPE_THRESHOLD && Math.abs(vx) > SWIPE_VELOCITY_THRESHOLD) {
                    if (dx < 0) nextBlock() else prevBlock()
                    return true
                }
                return false
            }
        })
        binding.txtContent.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        applyTheme()
        applyHideBars()
        applyTextSize()

        if (chapters.isEmpty()) {
            showLibraryDialog()
        }
    }

    // ========= MENU =========
    private fun toggleMenu() {
        val opening = binding.menuPanel.visibility != View.VISIBLE
        if (opening) updateCurrentBookBar()
        binding.menuPanel.visibility = if (opening) View.VISIBLE else View.GONE
    }

    private fun updateCurrentBookBar() {
        val hasBook = chapters.isNotEmpty()
        binding.currentBookBar.visibility = if (hasBook) View.VISIBLE else View.GONE
        if (!hasBook) return
        val label = if (currentTitle.isNotBlank()) currentTitle else fileLabel(currentUri)
        val pct = progressPercent()
        binding.txtCurrentBook.text =
            "$label — ${pct}% · Chapter ${chapterIndex + 1}/$totalChapters"
        binding.txtCurrentBook.setTextColor(themeColors().second)
        binding.imgCurrentCover.setImageDrawable(makeCoverPlaceholder())
        if (currentCover.isNotBlank() && File(currentCover).exists()) {
            loadThumb(currentCover, 220)?.let { binding.imgCurrentCover.setImageBitmap(it) }
        }
    }

    private fun makeCoverPlaceholder(): Drawable {
        val d = GradientDrawable()
        d.setColor(0xFF5A5A5A.toInt())
        d.setStroke(dp(1), 0xFF8A8A8A.toInt())
        d.cornerRadius = dp(3).toFloat()
        return d
    }

    private fun loadThumb(path: String, target: Int = 160): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                inSampleSize = sample
            })
        } catch (e: Exception) { null }
    }

    // ========= LIBRARY =========

    private fun loadLibrary(): MutableList<LibraryEntry> {
        val raw = libraryPrefs().getString("entries", "") ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LibraryEntry(
                    o.optString("title"), o.optString("uri"),
                    o.optInt("ch", 0), o.optInt("bl", 0),
                    o.optInt("progress", 0), o.optLong("ts", 0),
                    o.optString("cover", "")
                )
            }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }

    private fun saveLibrary(list: List<LibraryEntry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().put("title", e.title).put("uri", e.uri)
                .put("ch", e.chapter).put("bl", e.block)
                .put("progress", e.progress).put("ts", e.timestamp).put("cover", e.cover))
        }
        libraryPrefs().edit().putString("entries", arr.toString()).apply()
    }

    private fun updateLibrary(title: String, uri: String, chapter: Int, block: Int) {
        val list = loadLibrary().toMutableList()
        val idx = list.indexOfFirst { it.uri == uri }
        val progress = if (totalBlocks > 0) (globalBlockIndex() * 100 / totalBlocks) else 0
        val label = if (title.isNotBlank()) title else fileLabel(uri)
        val entry = LibraryEntry(label.ifBlank { uri }, uri, chapter, block, progress, System.currentTimeMillis(), currentCover)
        if (idx >= 0) list[idx] = entry else list.add(0, entry)
        if (list.size > 50) list.removeAt(list.size - 1)
        saveLibrary(list)
    }

    private fun showLibraryDialog() {
        val list = loadLibrary()
        if (list.isEmpty()) {
            showMessage("Tap \"Open EPUB\" to select a book.")
            return
        }
        val adapter = object : ArrayAdapter<LibraryEntry>(this, 0, list) {
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.library_item, parent, false)
                val e = getItem(pos) ?: return row
                val cover = row.findViewById<ImageView>(R.id.imgCover)
                val title = row.findViewById<TextView>(R.id.txtTitle)
                val sub = row.findViewById<TextView>(R.id.txtSub)
                cover.setImageDrawable(makeCoverPlaceholder())
                if (e.cover.isNotBlank() && File(e.cover).exists()) {
                    loadThumb(e.cover, 220)?.let { cover.setImageBitmap(it) }
                }
                title.text = if (e.title.isNotBlank()) e.title else fileLabel(e.uri)
                sub.text = if (e.progress > 0) "${e.progress}% read" else ""
                return row
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Recent books")
            .setAdapter(adapter) { _, which ->
                val entry = list[which]
                val uri = Uri.parse(entry.uri)
                loadEpub(uri, entry.chapter, entry.block)
            }
            .setPositiveButton("Open EPUB") { _, _ -> openEpubPicker() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun fileLabel(uriString: String): String {
        return try {
            val uri = Uri.parse(uriString)
            val name = queryDisplayName(uri)
            if (name.isNullOrBlank() || name.endsWith(":") || name.startsWith("/")) {
                uri.lastPathSegment?.substringAfterLast('/') ?: "Book"
            } else name
        } catch (e: Exception) { "Book" }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(
            android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }

    // ========= EPUB OPENING / PARSING =========

    private fun openEpubPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/epub+zip", "application/octet-stream", "application/x-mobipocket-ebook"
            ))
        }
        openDocumentLauncher.launch(intent)
    }

    private fun loadEpub(uri: Uri, jumpChapter: Int? = null, jumpBlock: Int? = null) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        currentUri = uri.toString()
        showMessage("Loading…")
        thread {
            try {
                val input = contentResolver.openInputStream(uri)
                val chaps = input?.use { EpubTextExtractor.extractChapters(it) } ?: emptyList()
                if (chaps.isNotEmpty()) {
                    val cover = try {
                        val bytes = contentResolver.openInputStream(uri)?.use { EpubTextExtractor.extractCover(it) }
                        if (bytes != null) saveCover(bytes) else ""
                    } catch (e: Exception) { "" }
                    runOnUiThread { currentCover = cover }
                }
                runOnUiThread {
                    if (chaps.isEmpty()) { showMessage("[No readable content in this EPUB]"); return@runOnUiThread }
                    presentChapters(chaps, jumpChapter, jumpBlock)
                }
            } catch (e: Exception) {
                runOnUiThread { showMessage("Error loading EPUB:\n${e.message}") }
            }
        }
    }

    private fun saveCover(bytes: ByteArray): String {
        return try {
            val dir = File(filesDir, "covers")
            dir.mkdirs()
            val f = File(dir, "${uriKey(currentUri)}.img")
            f.writeBytes(bytes)
            f.absolutePath
        } catch (e: Exception) { "" }
    }

    private fun presentChapters(chaps: List<EpubTextExtractor.Chapter>, jumpChapter: Int? = null, jumpBlock: Int? = null) {
        chapters = chaps
        stopTts()
        cachedLang = null
        tts.setLanguageLock(null)
        totalChapters = chapters.size
        chapterIndex = 0
        buildBlocks()
        computeTotalBlocks()
        if (jumpChapter != null && jumpChapter in chapters.indices) {
            chapterIndex = jumpChapter
            buildBlocks()
            blockIndex = jumpBlock?.coerceIn(0, blocks.size - 1) ?: 0
            renderBlock()
            savePosition()
        } else {
            val pos = loadPosition(currentUri)
            if (pos != null) askResume(pos.first, pos.second) else renderBlock()
        }
        updateCurrentBookBar()
        updateLibraryEntry()
    }

    private fun askResume(chapter: Int, block: Int) {
        if (chapter !in chapters.indices) { renderBlock(); return }
        val chapTitle = chapters[chapter].title
        AlertDialog.Builder(this)
            .setTitle("Resume reading?")
            .setMessage("Chapter: $chapTitle\nBlock: $block")
            .setPositiveButton("Resume") { _, _ ->
                chapterIndex = chapter; buildBlocks()
                blockIndex = block.coerceIn(0, blocks.size - 1)
                renderBlock(); savePosition()
            }
            .setNegativeButton("Start over") { _, _ -> renderBlock() }
            .show()
    }

    // ========= PAGE-FITTING BLOCKS =========

    private fun computeTotalBlocks() {
        totalBlocks = 0
        for (ch in chapters) totalBlocks += countBlocks(ch.paragraphs)
    }

    private fun countBlocks(paragraphs: List<String>): Int {
        val maxChars = measureMaxChars()
        if (paragraphs.isEmpty()) return 0
        val processed = mutableListOf<String>()
        for (para in paragraphs) {
            if (para.length <= maxChars) processed.add(para)
            else { var i = 0; while (i < para.length) { processed.add(para.substring(i, minOf(i + maxChars, para.length))); i += maxChars } }
        }
        var count = 0; var i = 0; while (i < processed.size) { count++; i += 1 }
        return count.coerceAtLeast(1)
    }

    private fun measureMaxChars(): Int {
        binding.txtContent.post {
            val h = binding.txtContent.height
            if (h <= 0) return@post
        }
        val h = binding.txtContent.height
        if (h <= 0) return 2500
        val lh = binding.txtContent.paint.fontMetrics.let { it.bottom - it.top }
        val maxLines = (h / lh).toInt().coerceAtLeast(1)
        val w = binding.txtContent.width - binding.txtContent.paddingStart - binding.txtContent.paddingEnd
        val sample = "abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz".toCharArray()
        val charsPerLine = binding.txtContent.paint.breakText(sample, 0, sample.size, w.toFloat(), null)
            .coerceAtLeast(20)
        val charsPerPage = maxLines * charsPerLine
        return (charsPerPage * 0.85).toInt().coerceIn(800, 8000)
    }

    private fun buildBlocks() {
        if (chapters.isEmpty()) { blocks = emptyList(); return }
        val chapter = chapters[chapterIndex]
        currentTitle = chapter.title
        val maxChars = measureMaxChars()
        blocks = buildTextPages(chapter.paragraphs, maxChars)
        if (blocks.isEmpty()) blocks = listOf("[Empty chapter]")
        blockIndex = 0
    }

    private fun buildTextPages(paragraphs: List<String>, maxChars: Int): List<String> {
        if (paragraphs.isEmpty()) return emptyList()
        val processed = mutableListOf<String>()
        for (para in paragraphs) {
            if (para.length <= maxChars) processed.add(para)
            else {
                var i = 0
                while (i < para.length) {
                    processed.add(para.substring(i, minOf(i + maxChars, para.length)))
                    i += maxChars
                }
            }
        }
        val pages = mutableListOf<String>()
        var i = 0
        while (i < processed.size) { pages.add(processed[i]); i++ }
        return pages
    }

    private fun globalBlockIndex(): Int {
        var idx = 0
        for (i in 0 until chapterIndex) idx += countBlocks(chapters[i].paragraphs)
        return idx + blockIndex
    }

    private fun progressPercent(): Int {
        if (totalBlocks == 0) return 0
        return (globalBlockIndex() * 100 / totalBlocks).coerceIn(0, 100)
    }

    // ========= RENDERING =========

    private fun buildHeader(): String {
        if (chapters.isEmpty()) return ""
        val lines = mutableListOf<String>()
        if (showTitle && currentTitle.isNotBlank()) lines.add("▌$currentTitle")
        if (showProgress) {
            val pct = progressPercent()
            lines.add("— Chapter ${chapterIndex + 1}/${totalChapters} · Block ${blockIndex + 1}/${blocks.size} · $pct% —")
        }
        return if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n\n"
    }

    private fun renderBlock(useSpannable: Boolean = false) {
        if (blocks.isEmpty()) return
        val header = buildHeader()
        headerLength = header.length
        binding.btnPlay.text = if (ttsActive) "Play \u25A0" else "Play"
        if (useSpannable) {
            val sp = SpannableString(header + blocks[blockIndex])
            currentSpannable = sp
            binding.txtContent.text = sp
        } else {
            currentSpannable = null
            binding.txtContent.text = header + blocks[blockIndex]
        }
    }

    private fun showMessage(msg: String) {
        blocks = emptyList(); chapterIndex = 0; blockIndex = 0; currentSpannable = null
        binding.txtContent.text = msg
    }

    // ========= NAVIGATION =========

    private fun nextBlock() {
        if (blocks.isEmpty()) return
        if (blockIndex < blocks.size - 1) blockIndex++
        else if (chapterIndex < chapters.size - 1) { chapterIndex++; buildBlocks() }
        else { toast("You've reached the end of the book"); return }
        if (ttsActive) { tts.stopAll(); speakBlock() } else renderBlock()
        savePosition(); updateLibraryEntry()
    }

    private fun prevBlock() {
        if (blocks.isEmpty()) return
        if (blockIndex > 0) blockIndex--
        else if (chapterIndex > 0) { chapterIndex--; buildBlocks(); blockIndex = blocks.size - 1 }
        else { toast("You're at the start of the book"); return }
        if (ttsActive) { tts.stopAll(); speakBlock() } else renderBlock()
        savePosition(); updateLibraryEntry()
    }

    private fun updateLibraryEntry() {
        if (currentUri.isBlank() || chapters.isEmpty()) return
        updateLibrary(currentTitle, currentUri, chapterIndex, blockIndex)
    }

    // ========= TTS =========

    private fun toggleTts() {
        if (ttsActive) stopTts() else startTts()
    }

    private fun startTts() {
        if (blocks.isEmpty()) { toast("Open an EPUB first"); return }
        ttsActive = true
        ttsBusy = false
        tts.ttsMode = if (ttsMode == "online") "online" else "auto"
        ttsOnline = tts.onlineMode
        var lang = cachedLang
        if (lang == null) {
            val sample = buildLanguageSample()
            lang = if (sample.isBlank()) "en" else tts.detectLanguage(sample)
            cachedLang = lang
            tts.setLanguageLock(lang)
        }
        acquireWakeLock()
        binding.btnPlay.text = "Play \u25A0"
        speakBlock()
    }

    private fun stopTts() {
        ttsActive = false
        ttsOnline = false
        ttsBusy = false
        tts.stopAll()
        releaseWakeLock()
        currentChunkOffsets = IntArray(0); lastUtteranceId = ""
        if (blocks.isNotEmpty()) renderBlock()
    }

    private fun buildLanguageSample(): String {
        if (chapters.isEmpty()) return ""
        val text = chapters.joinToString("\n") { c -> c.paragraphs.joinToString(" ") }
        return text.take(3000)
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "epubreader:tts")
            }
            wakeLock?.acquire()
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
    }

    private fun speakBlock() {
        if (!ttsActive) return
        if (blocks.isEmpty() || blockIndex !in blocks.indices) { finishReading(); return }
        ttsBusy = true
        renderBlock(useSpannable = true)
        val text = blocks[blockIndex]
        if (text.isBlank()) { nextBlockForTts(); return }

        if (ttsOnline) {
            tts.speakOnline(text, cachedLang)
        } else {
            val chunks = text.chunked(3000)
            currentChunkOffsets = IntArray(chunks.size)
            var offset = 0
            for (i in chunks.indices) { currentChunkOffsets[i] = offset; offset += chunks[i].length }
            currentChunks = chunks
            val single = "s${chapterIndex}_${blockIndex}"
            val multi = "m${chapterIndex}_${blockIndex}_"
            var localOk = true
            for (i in chunks.indices) {
                val id = if (chunks.size == 1) single else multi + i
                val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                if (!tts.speakLocal(chunks[i], id, mode)) { localOk = false; break }
            }
            if (!localOk) {
                ttsOnline = true
                tts.speakOnline(text, cachedLang)
            } else {
                lastUtteranceId = if (chunks.size == 1) single else multi + (chunks.size - 1)
            }
        }
    }

    private fun onUtteranceDone(utteranceId: String?) {
        if (!ttsActive) return
        if (!ttsOnline && utteranceId == lastUtteranceId) {
            val elapsed = System.currentTimeMillis() - tts.localSpeechStartedAt
            if (elapsed < 500 && blocks[blockIndex].length > 20) {
                ttsOnline = true
                tts.stopAll()
                speakBlock()
            } else {
                ttsBusy = false
                nextBlockForTts()
            }
        }
    }

    private fun nextBlockForTts() {
        if (!ttsActive) return
        if (blockIndex < blocks.size - 1) blockIndex++
        else if (chapterIndex < chapters.size - 1) { chapterIndex++; buildBlocks() }
        else { finishReading(); return }
        savePosition()
        binding.txtContent.postDelayed({ speakBlock() }, 50)
    }

    private fun finishReading() {
        ttsActive = false; ttsOnline = false; ttsBusy = false; tts.stopAll()
        releaseWakeLock()
        binding.btnPlay.text = "Play"
        if (blocks.isNotEmpty()) renderBlock()
        toast("Finished reading")
    }

    // ========= HIGHLIGHT =========

    private fun highlightTtsRange(utteranceId: String?, start: Int, end: Int) {
        val sp = currentSpannable ?: return
        if (start < 0 || end < start) return
        val chunkIndex = chunkIndexForId(utteranceId)
        if (chunkIndex < 0 || chunkIndex >= currentChunkOffsets.size) return
        var sStart = headerLength + currentChunkOffsets[chunkIndex] + start
        var sEnd = headerLength + currentChunkOffsets[chunkIndex] + end
        if (sEnd > sp.length) sEnd = sp.length
        while (sStart > headerLength && !isSentenceBreak(sp[sStart - 1])) sStart--
        while (sEnd < sp.length && !isSentenceBreak(sp[sEnd - 1])) sEnd++
        for (span in sp.getSpans(0, sp.length, BackgroundColorSpan::class.java)) sp.removeSpan(span)
        sp.setSpan(BackgroundColorSpan(TTS_HIGHLIGHT_COLOR), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun highlightSentence(start: Int, end: Int) {
        val sp = currentSpannable ?: return
        var sStart = headerLength + start
        var sEnd = headerLength + end
        if (sEnd > sp.length) sEnd = sp.length
        while (sStart > headerLength && !isSentenceBreak(sp[sStart - 1])) sStart--
        while (sEnd < sp.length && !isSentenceBreak(sp[sEnd - 1])) sEnd++
        for (span in sp.getSpans(0, sp.length, BackgroundColorSpan::class.java)) sp.removeSpan(span)
        sp.setSpan(BackgroundColorSpan(TTS_HIGHLIGHT_COLOR), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun chunkIndexForId(utteranceId: String?): Int {
        val id = utteranceId ?: return -1
        return if (id.startsWith("m")) id.substringAfterLast('_').toIntOrNull() ?: -1 else 0
    }

    private fun isSentenceBreak(c: Char): Boolean = c == '.' || c == '!' || c == '?' || c == '\n'

    // ========= VOICE / SPEED / TEXT SIZE =========

    private fun showVoiceSelector() {
        val voices: List<Voice> = tts.getAvailableVoices()
        if (voices.isEmpty()) { toast("No voices available"); return }
        val names: Array<CharSequence> = Array(voices.size) { voices[it].name as CharSequence }
        AlertDialog.Builder(this).setTitle("Select Voice")
            .setItems(names) { _, which -> tts.setVoice(voices[which]); toast("Voice: ${names[which]}") }
            .show()
    }

    private fun showSpeedDialog() {
        val speeds = arrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val labels = speeds.map { if (it == 1.0f) "1.0× (normal)" else "${it}×" }.toTypedArray()
        val current = speeds.indexOfFirst { it == ttsSpeed }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("TTS speed")
            .setSingleChoiceItems(labels, current) { _, which ->
                ttsSpeed = speeds[which]; prefs().edit().putFloat("tts_speed", ttsSpeed).apply(); tts.setSpeed(ttsSpeed)
            }
            .setNegativeButton("Close", null).show()
    }

    private fun changeTextSize(delta: Float) {
        textSize = (textSize + delta).coerceIn(10f, 28f)
        prefs().edit().putFloat("text_size", textSize).apply()
        applyTextSize(); toast("Text size: ${textSize.toInt()}sp")
    }

    private fun applyTextSize() { binding.txtContent.textSize = textSize }

    private fun applyHideBars() {
        val vis = if (hideBottomBars) View.GONE else View.VISIBLE
        binding.bottomBarRow1.visibility = vis
        binding.bottomBarRow2.visibility = vis
    }

    // ========= SETTINGS =========

    private fun showSettingsDialog() {
        val items = mutableListOf<String>()
        val actions = mutableListOf<Int>()
        fun add(label: String, action: Int) { items.add(label); actions.add(action) }
        add("Text size: ${textSize.toInt()}sp", 0)
        add("Show chapter title: ${if (showTitle) "on" else "off"}", 1)
        add("Show block progress: ${if (showProgress) "on" else "off"}", 2)
        add("TTS speed: ${speedLabel()}", 3)
        add("TTS voice: ${if (ttsMode == "online") "Online only" else "Auto (local + online)"}", 4)
        add("Theme: ${themeNames[themeMode]}", 5)
        add("Hide bottom bars: ${if (hideBottomBars) "on" else "off"}", 6)
        add("Clear saved positions", 7)
        add("Clear library", 8)
        AlertDialog.Builder(this).setTitle("Settings")
            .setItems(items.toTypedArray()) { _, which ->
                when (actions[which]) {
                    0 -> askInt("Text size (sp)", textSize.toInt(), 10, 28) { textSize = it.toFloat(); applyTextSize() }
                    1 -> { showTitle = !showTitle; prefs().edit().putBoolean("show_title", showTitle).apply(); renderBlock() }
                    2 -> { showProgress = !showProgress; prefs().edit().putBoolean("show_progress", showProgress).apply(); renderBlock() }
                    3 -> showSpeedDialog()
                    4 -> pickTtsVoiceMode()
                    5 -> toggleTheme()
                    6 -> { hideBottomBars = !hideBottomBars; prefs().edit().putBoolean("hide_bottom_bars", hideBottomBars).apply(); applyHideBars() }
                    7 -> clearPositions()
                    8 -> clearLibrary()
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun pickTtsVoiceMode() {
        val modes = arrayOf("Auto (local engine + online fallback)", "Online always (Google voice)")
        val current = if (ttsMode == "online") 1 else 0
        AlertDialog.Builder(this).setTitle("TTS voice mode")
            .setSingleChoiceItems(modes, current) { _, which ->
                ttsMode = if (which == 1) "online" else "auto"
                prefs().edit().putString("tts_mode", ttsMode).apply()
                tts.ttsMode = ttsMode
            }
            .setNegativeButton("Close", null).show()
    }

    private fun speedLabel(): String = if (ttsSpeed == 1.0f) "1.0× (normal)" else "${ttsSpeed}×"

    private fun askInt(label: String, current: Int, min: Int, max: Int, onSet: (Int) -> Unit) {
        val input = EditText(this); input.inputType = InputType.TYPE_CLASS_NUMBER; input.setText(current.toString())
        AlertDialog.Builder(this).setTitle(label).setView(input)
            .setPositiveButton("OK") { _, _ ->
                val v = input.text.toString().trim().toIntOrNull()
                if (v != null && v in min..max) onSet(v) else toast("Enter $min–$max")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun clearPositions() {
        AlertDialog.Builder(this).setTitle("Clear saved positions")
            .setMessage("Remove auto-saved reading positions for all books?")
            .setPositiveButton("Clear") { _, _ -> positionPrefs().edit().clear().apply(); toast("Positions cleared") }
            .setNegativeButton("Cancel", null).show()
    }

    private fun clearLibrary() {
        AlertDialog.Builder(this).setTitle("Clear library")
            .setMessage("Remove all books from the library?")
            .setPositiveButton("Clear") { _, _ -> libraryPrefs().edit().clear().apply(); toast("Library cleared") }
            .setNegativeButton("Cancel", null).show()
    }

    // ========= THEME =========

    private fun themeColors(): Pair<Int, Int> = when (themeMode) {
        1 -> getColor(R.color.nightBackground) to getColor(R.color.nightText)
        2 -> getColor(R.color.oledBackground) to getColor(R.color.oledText)
        else -> getColor(R.color.dayBackground) to getColor(R.color.dayText)
    }

    private fun applyTheme() {
        val (bg, fg) = themeColors()
        binding.rootLayout.setBackgroundColor(bg)
        binding.txtContent.setTextColor(fg)
        binding.txtContent.textSize = textSize
        val buttons = listOf(
            binding.btnOpenEpub, binding.btnMenu, binding.btnPlay, binding.btnPause,
            binding.btnStop, binding.btnPrev, binding.btnNext, binding.btnBookmark,
            binding.btnBookmarks, binding.btnTheme, binding.btnTextSmall,
            binding.btnTextBig, binding.btnSpeed, binding.btnSelectVoice, binding.btnSettings,
            binding.btnRecents
        )
        for (b in buttons) {
            if (!defaultButtonTint.containsKey(b.id)) {
                defaultButtonBg[b.id] = b.background
                defaultButtonTint[b.id] = b.backgroundTintList
                defaultButtonText[b.id] = b.textColors
            }
            when (themeMode) {
                2 -> {
                    val d = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE; setColor(bg)
                        setStroke(dp(1), getColor(R.color.oledBorder)); cornerRadius = dp(4).toFloat()
                    }
                    b.background = d; b.backgroundTintList = ColorStateList.valueOf(bg); b.setTextColor(fg)
                }
                1 -> { b.background = defaultButtonBg[b.id]; b.backgroundTintList = ColorStateList.valueOf(getColor(R.color.nightButton)); b.setTextColor(fg) }
                else -> { b.background = defaultButtonBg[b.id]; b.backgroundTintList = defaultButtonTint[b.id]; defaultButtonText[b.id]?.let { b.setTextColor(it) } }
            }
        }
    }

    private fun toggleTheme() {
        themeMode = (themeMode + 1) % 3
        prefs().edit().putInt("theme", themeMode).apply(); applyTheme()
        toast("Theme: ${themeNames[themeMode]}")
    }

    // ========= POSITION SAVING =========

    private fun savePosition() {
        if (currentUri.isBlank() || chapters.isEmpty()) return
        val root = try { JSONObject(positionPrefs().getString("positions", "{}")!!) } catch (e: Exception) { JSONObject() }
        root.put(uriKey(currentUri), JSONObject().put("ch", chapterIndex).put("bl", blockIndex))
        positionPrefs().edit().putString("positions", root.toString()).apply()
    }

    private fun loadPosition(uri: String): Pair<Int, Int>? {
        val root = try { JSONObject(positionPrefs().getString("positions", "{}")!!) } catch (e: Exception) { JSONObject() }
        val obj = root.optJSONObject(uriKey(uri)) ?: return null
        return Pair(obj.optInt("ch", 0), obj.optInt("bl", 0))
    }

    private fun uriKey(uri: String): String =
        MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // ========= BOOKMARKS =========

    private fun saveBookmark() {
        if (currentUri.isBlank() || blocks.isEmpty()) { toast("Open a book first"); return }
        val list = loadBookmarks()
        val idx = list.indexOfFirst { it.uri == currentUri }
        val bm = Bookmark(if (currentTitle.isBlank()) currentUri else currentTitle, currentUri, chapterIndex, blockIndex)
        if (idx >= 0) list[idx] = bm else list.add(0, bm)
        saveBookmarks(list); toast("Bookmark saved")
    }

    private fun showBookmarksDialog() {
        val list = loadBookmarks()
        if (list.isEmpty()) { AlertDialog.Builder(this).setMessage("No bookmarks yet.").setPositiveButton("OK", null).show(); return }
        val titles = list.map { it.title }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Bookmarks")
            .setItems(titles) { _, which -> openBookmark(list[which]) }
            .setNeutralButton("Remove…") { _, _ -> showRemoveBookmarksDialog() }
            .setNegativeButton("Close", null).show()
    }

    private fun openBookmark(bm: Bookmark) {
        if (bm.uri == currentUri) {
            stopTts(); chapterIndex = bm.chapter.coerceIn(0, chapters.size - 1)
            buildBlocks(); blockIndex = bm.block.coerceIn(0, blocks.size - 1)
            renderBlock(); savePosition(); return
        }
        try { val uri = Uri.parse(bm.uri); contentResolver.openInputStream(uri)?.use { _ -> loadEpub(uri, bm.chapter, bm.block) } ?: run { toast("Re-select the file"); openEpubPicker() }
        } catch (e: Exception) { toast("Re-select the file"); openEpubPicker() }
    }

    private fun showRemoveBookmarksDialog() {
        val list = loadBookmarks()
        if (list.isEmpty()) { toast("No bookmarks to remove"); return }
        val titles = list.map { it.title }.toTypedArray(); val checked = BooleanArray(list.size)
        AlertDialog.Builder(this).setTitle("Remove bookmarks")
            .setMultiChoiceItems(titles, checked) { _, i, isChecked -> checked[i] = isChecked }
            .setPositiveButton("Remove") { _, _ ->
                val toRemove = list.indices.filter { checked[it] }.map { list[it].uri }.toSet()
                if (toRemove.isEmpty()) toast("Nothing selected")
                else { saveBookmarks(loadBookmarks().filterNot { it.uri in toRemove }); toast("Removed ${toRemove.size} bookmark(s)") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun loadBookmarks(): MutableList<Bookmark> {
        val raw = bookmarkPrefs().getString("list", "") ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { val o = arr.getJSONObject(it); Bookmark(o.optString("title"), o.optString("uri"), o.optInt("ch"), o.optInt("bl")) }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }

    private fun saveBookmarks(list: List<Bookmark>) {
        val arr = JSONArray(); for (b in list) arr.put(JSONObject().put("title", b.title).put("uri", b.uri).put("ch", b.chapter).put("bl", b.block))
        bookmarkPrefs().edit().putString("list", arr.toString()).apply()
    }

    // ========= PREFS =========

    private fun prefs() = getSharedPreferences("reader", Context.MODE_PRIVATE)
    private fun positionPrefs() = getSharedPreferences("positions", Context.MODE_PRIVATE)
    private fun bookmarkPrefs() = getSharedPreferences("bookmarks", Context.MODE_PRIVATE)
    private fun libraryPrefs() = getSharedPreferences("library", Context.MODE_PRIVATE)

    private fun loadPrefs() {
        textSize = prefs().getFloat("text_size", 16f)
        ttsSpeed = prefs().getFloat("tts_speed", 1.0f)
        themeMode = prefs().getInt("theme", 0).let { if (it in 0..2) it else 0 }
        showTitle = prefs().getBoolean("show_title", true)
        showProgress = prefs().getBoolean("show_progress", true)
        ttsMode = prefs().getString("tts_mode", "auto") ?: "auto"
        hideBottomBars = prefs().getBoolean("hide_bottom_bars", false)
    }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onPause() {
        super.onPause()
        savePosition(); updateLibraryEntry()
    }

    override fun onDestroy() { releaseWakeLock(); tts.shutdown(); super.onDestroy() }

    companion object {
        private const val TTS_HIGHLIGHT_COLOR = 0x99FFC107.toInt()
    }
}

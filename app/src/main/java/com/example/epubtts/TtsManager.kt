package com.example.epubtts

import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread

data class SentenceRange(
    val start: Int,
    val end: Int,
    val text: String
)

class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var isReady = false
    private var rate = 1.0f
    private var pendingVoice: Voice? = null
    private var progressListener: UtteranceProgressListener? = null

    var ttsMode = "auto"
    var onSentenceHighlight: ((Int, Int) -> Unit)? = null
    var onOnlineUtteranceDone: (() -> Unit)? = null
    var onOnlineError: (() -> Unit)? = null

    private var mediaPlayer: MediaPlayer? = null
    private var onlineSentences: List<SentenceRange> = emptyList()
    private var onlineSentenceIndex = 0
    private var onlineFailures = 0
    private var totalOnlineFailures = 0
    var localSpeechStartedAt = 0L
    var lastOnlineError: String? = null

    val ready: Boolean get() = isReady

    val onlineMode: Boolean get() = ttsMode == "online" || (ttsMode == "auto" && !isReady)

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.LANG_NOT_SUPPORTED
            isReady = result != TextToSpeech.LANG_MISSING_DATA &&
                      result != TextToSpeech.LANG_NOT_SUPPORTED
            if (isReady) {
                try { tts?.setSpeechRate(rate) } catch (_: Exception) {}
                pendingVoice?.let { v ->
                    try { tts?.voice = v } catch (_: Exception) {}
                }
                progressListener?.let { l ->
                    try { tts?.setOnUtteranceProgressListener(l) } catch (_: Exception) {}
                }
            }
            Log.i("TTS", "TTS initialized: $isReady")
        } else {
            Log.e("TTS", "Initialization failed")
            isReady = false
        }
    }

    fun setOnUtteranceProgressListener(l: UtteranceProgressListener) {
        progressListener = l
        if (isReady) {
            try { tts?.setOnUtteranceProgressListener(l) } catch (_: Exception) {}
        }
    }

    fun setSpeed(speed: Float) {
        rate = speed
        if (isReady) {
            try { tts?.setSpeechRate(speed) } catch (_: Exception) {}
        }
    }

    fun getAvailableVoices(): List<Voice> = tts?.voices?.toList() ?: emptyList()

    fun setVoice(voice: Voice) {
        pendingVoice = voice
        if (isReady) {
            try { tts?.voice = voice } catch (_: Exception) {}
        }
    }

    // --- Local TTS ---
    fun speakLocal(text: String, utteranceId: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH): Boolean {
        if (!isReady || text.isBlank()) return false
        val result = tts?.speak(text, queueMode, null, utteranceId) ?: -1
        if (result == TextToSpeech.SUCCESS) localSpeechStartedAt = System.currentTimeMillis()
        return result == TextToSpeech.SUCCESS
    }

    // --- Online TTS ---
    fun splitSentences(text: String): List<SentenceRange> {
        val result = mutableListOf<SentenceRange>()
        var i = 0
        while (i < text.length) {
            var s = i
            while (s < text.length && text[s].isWhitespace()) s++
            if (s >= text.length) break
            var e = s
            while (e < text.length && text[e] != '.' && text[e] != '!' &&
                text[e] != '?' && text[e] != '\n'
            ) e++
            var end = e
            if (end < text.length && text[end] != '\n') end++
            result.add(SentenceRange(s, end, text.substring(s, end).trim()))
            i = end
        }
        return if (result.isEmpty()) {
            listOf(SentenceRange(0, text.length, text.trim()))
        } else result
    }

    fun speakOnline(text: String) {
        if (text.isBlank()) return
        onlineSentences = splitSentences(text)
        onlineSentenceIndex = 0
        onlineFailures = 0
        totalOnlineFailures = 0
        lastOnlineError = null
        playOnlineSentence()
    }

    private fun playOnlineSentence() {
        if (onlineSentenceIndex >= onlineSentences.size) {
            onOnlineUtteranceDone?.invoke()
            return
        }
        val sentence = onlineSentences[onlineSentenceIndex]
        val chunks = chunkText(sentence.text, 180)
        onSentenceHighlight?.invoke(sentence.start, sentence.end)
        playChunks(sentence, chunks, 0)
    }

    private fun playChunks(sentence: SentenceRange, chunks: List<String>, ci: Int) {
        if (ci >= chunks.size) {
            onOnlineSentenceDone()
            return
        }
        val chunk = chunks[ci]
        thread {
            try {
                val lang = detectLang(chunks.joinToString(" "))
                val bytes = fetchOnlineSpeech(chunk, lang)
                runOnUiThread { playMp3(bytes) { playChunks(sentence, chunks, ci + 1) } }
            } catch (e: Exception) {
                lastOnlineError = e.message
                runOnUiThread {
                    onlineFailures++
                    totalOnlineFailures++
                    if (totalOnlineFailures > 6) {
                        stopAll()
                        onOnlineError?.invoke()
                    } else {
                        playChunks(sentence, chunks, ci + 1)
                    }
                }
            }
        }
    }

    private fun onOnlineSentenceDone() {
        onlineSentenceIndex++
        onlineFailures = 0
        playOnlineSentence()
    }

    private fun playMp3(bytes: ByteArray, onDone: () -> Unit) {
        val file = File.createTempFile("tts", ".mp3", appContext.cacheDir)
        try {
            file.writeBytes(bytes)
            mediaPlayer?.release()
            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { file.delete(); onDone() }
            mp.setOnErrorListener { _, _, _ -> file.delete(); stopAll(); onOnlineError?.invoke(); true }
            mp.setOnPreparedListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && rate != 1.0f) {
                    try { mp.playbackParams = mp.playbackParams.setSpeed(rate) } catch (_: Exception) {}
                }
                it.start()
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            file.delete()
            stopAll()
            onOnlineError?.invoke()
        }
    }

    private fun fetchOnlineSpeech(text: String, lang: String): ByteArray {
        val hosts = listOf(
            "https://translate.googleapis.com/translate_tts?client=gtx&ie=UTF-8&tl=%s&q=%s",
            "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=%s&q=%s"
        )
        val langs = listOf(lang, "en").distinct()
        var lastError: Exception? = null
        for (l in langs) {
            for (template in hosts) {
                try {
                    val q = URLEncoder.encode(text, "UTF-8")
                    val url = String.format(template, l, q)
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 20000
                    conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 13; EpubReader) AppleWebKit/537.36")
                    conn.setRequestProperty("Accept", "audio/mpeg")
                    conn.setRequestProperty("Referer", "https://translate.google.com/")
                    if (conn.responseCode == 200) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        if (bytes.size > 500) return bytes
                        lastError = IOException("Audio too small: ${bytes.size}B")
                    } else {
                        lastError = IOException("HTTP ${conn.responseCode}")
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }
        throw lastError ?: IOException("Online voice failed")
    }

    private fun chunkText(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + maxLen, text.length)
            if (end < text.length) {
                val space = text.lastIndexOf(' ', end)
                if (space > start + maxLen / 2) end = space
            }
            val s = start.coerceAtMost(text.length - 1)
            var e = end
            while (e > s && text[e - 1].isWhitespace()) e--
            if (e > s) chunks.add(text.substring(s, e))
            start = maxOf(end, s + 1)
        }
        return if (chunks.isEmpty()) listOf(text) else chunks
    }

    private fun detectLang(text: String): String {
        val t = text.take(500)
        val lower = t.lowercase()
        val scores = mapOf(
            "en" to listOf("the", "and", "that", "with", "this", "you", "for", "are", "was"),
            "es" to listOf("que", "de", "la", "el", "y", "los", "las", "una", "para", "con"),
            "fr" to listOf("les", "des", "que", "une", "pour", "avec", "dans", "est", "sur"),
            "it" to listOf("che", "per", "con", "una", "non", "alla", "sono", "come", "più"),
            "de" to listOf("der", "die", "und", "das", "ist", "mit", "nicht", "ein"),
            "pt" to listOf("que", "uma", "para", "com", "dos", "das", "não", "são", "mais"),
            "nl" to listOf("van", "het", "een", "voor", "niet", "met", "zijn", "als")
        )
        var best = "en"
        var bestScore = -1
        for ((lang, words) in scores) {
            val score = words.count { Regex("\\b$it\\b").containsMatchIn(lower) }
            if (score > bestScore) { bestScore = score; best = lang }
        }
        return if (bestScore >= 2) best else "en"
    }

    // --- Controls ---
    fun stopAll() {
        try { tts?.stop() } catch (_: Exception) {}
        mediaPlayer?.release()
        mediaPlayer = null
        onlineSentences = emptyList()
        onlineSentenceIndex = 0
    }

    fun pause() = stopAll()
    fun stop() = stopAll()

    fun shutdown() {
        stopAll()
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
    }

    private fun runOnUiThread(r: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(r)
    }
}

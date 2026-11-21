package com.fitsync.app.share

import android.content.Intent
import android.net.Uri

/**
 * 统一从系统分享 / 打开文件的 Intent 提取 URI，并去重保序。
 */
object ShareIntentUris {
    fun extract(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val collected = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let(collected::add)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty().forEach(collected::add)
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let(collected::add)
            }
        }
        val clip = intent.clipData
        if (clip != null) {
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index)?.uri?.let(collected::add)
            }
        }
        return collected.toList()
    }
}

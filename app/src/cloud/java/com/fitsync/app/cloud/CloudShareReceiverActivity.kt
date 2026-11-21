package com.fitsync.app.cloud

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.fitsync.app.share.ShareIntentUris
import com.fitsync.app.ui.components.FitCard
import com.fitsync.app.ui.components.FitTopBar
import com.fitsync.app.ui.components.IconBadge
import com.fitsync.app.ui.components.IconBadgeSize
import com.fitsync.app.ui.components.IconBadgeTone
import com.fitsync.app.ui.theme.FitSpacing
import com.fitsync.app.ui.theme.FitSyncTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File

/**
 * 云端版系统分享 / 打开文件接收器：将用户分享的 `.fit` 文件直接上传至云端活动库。
 */
class CloudShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uris = ShareIntentUris.extract(intent)

        setContent {
            FitSyncTheme {
                var message by remember { mutableStateOf("正在上传并校验 FIT 文件…") }
                var done by remember { mutableStateOf(false) }
                var successful by remember { mutableStateOf(true) }
                var requiresLogin by remember { mutableStateOf(false) }

                LaunchedEffect(uris) {
                    if (uris.isEmpty()) {
                        message = "没有收到可导入的 FIT 文件"
                        successful = false
                        done = true
                        return@LaunchedEffect
                    }
                    val store = CloudApp.instance.sessionStore
                    val token = store.currentToken()
                    if (token.isNullOrBlank()) {
                        message = "请先在 FitSync Cloud 登录账号，再通过系统分享导入 FIT 文件。"
                        successful = false
                        requiresLogin = true
                        done = true
                        return@LaunchedEffect
                    }

                    val apiBase = store.activeApiBaseUrl()
                    val outcomes = withContext(Dispatchers.IO) {
                        uris.map { uri -> uploadUri(uri, token, apiBase) }
                    }
                    requiresLogin = outcomes.any { it.unauthorized }
                    if (requiresLogin) {
                        store.clearSession()
                    }
                    successful = outcomes.all { it.ok }
                    message = outcomes.joinToString("\n") { it.message }
                    done = true
                }

                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    FitTopBar(
                        title = "导入 FIT 到云端",
                        subtitle = "直接上传至你的 FitSync 云端活动库",
                        modifier = Modifier.statusBarsPadding(),
                    )
                    Column(
                        modifier = Modifier.fillMaxSize().padding(FitSpacing.lg),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FitCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(FitSpacing.xxl),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                IconBadge(
                                    icon = when {
                                        !done -> Icons.Default.CloudUpload
                                        successful -> Icons.Default.CheckCircle
                                        else -> Icons.Default.ErrorOutline
                                    },
                                    size = IconBadgeSize.Lg,
                                    tone = when {
                                        !done -> IconBadgeTone.Neutral
                                        successful -> IconBadgeTone.Success
                                        else -> IconBadgeTone.Error
                                    },
                                )
                                Text(
                                    text = when {
                                        !done -> "正在上传"
                                        requiresLogin -> "需要登录"
                                        successful -> "导入完成"
                                        else -> "部分文件未导入"
                                    },
                                    modifier = Modifier.padding(top = FitSpacing.lg),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    text = message,
                                    modifier = Modifier.padding(top = FitSpacing.sm),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                if (done) {
                                    if (requiresLogin || successful) {
                                        Button(
                                            onClick = {
                                                startActivity(
                                                    Intent(this@CloudShareReceiverActivity, CloudMainActivity::class.java)
                                                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                                                )
                                                finish()
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(top = FitSpacing.xl),
                                        ) {
                                            Text(if (requiresLogin) "打开 FitSync Cloud 登录" else "打开 FitSync Cloud")
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { finish() },
                                        modifier = Modifier.fillMaxWidth().padding(top = FitSpacing.sm),
                                    ) {
                                        Text("完成")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private data class UploadOutcome(
        val ok: Boolean,
        val message: String,
        val unauthorized: Boolean = false,
    )

    private fun uploadUri(uri: Uri, token: String, apiBase: String): UploadOutcome {
        val displayName = resolveDisplayName(uri)
        val tempDir = File(cacheDir, "cloud_import_tmp").apply { mkdirs() }
        val tempFile = File(tempDir, "upload_${System.nanoTime()}.fit")
        return try {
            val copiedBytes = contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_FIT_BYTES) {
                            return UploadOutcome(false, "$displayName：文件超过 25MB 限制")
                        }
                        output.write(buffer, 0, read)
                    }
                    total
                }
            } ?: return UploadOutcome(false, "$displayName：无法读取文件内容")

            if (copiedBytes <= 0L) {
                return UploadOutcome(false, "$displayName：文件内容为空")
            }

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    displayName,
                    tempFile.asRequestBody("application/octet-stream".toMediaType()),
                )
                .build()
            val request = Request.Builder()
                .url("$apiBase/v1/activities/import")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()

            CloudApp.instance.httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.code == 401) {
                    return UploadOutcome(false, "登录已过期，请重新登录后再试", unauthorized = true)
                }
                val parsed = runCatching { JSONObject(raw) }.getOrNull()
                if (!response.isSuccessful) {
                    val errMsg = parsed?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: "上传失败（HTTP ${response.code}）"
                    return UploadOutcome(false, "$displayName：$errMsg")
                }
                val duplicate = parsed?.optBoolean("duplicate", false) == true
                val label = if (duplicate) "云端活动库已存在，未重复导入" else "已上传并保存至云端"
                UploadOutcome(true, "$displayName：$label")
            }
        } catch (err: Exception) {
            UploadOutcome(false, "$displayName：${err.message ?: "上传失败"}")
        } finally {
            tempFile.delete()
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        val queried = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        val raw = queried?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "activity.fit"
        return raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    companion object {
        private const val MAX_FIT_BYTES = 25L * 1024L * 1024L
    }
}

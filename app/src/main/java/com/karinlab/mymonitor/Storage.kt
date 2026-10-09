package com.karinlab.mymonitor

import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.File
import java.io.FileInputStream

/**
 * Always records to local app storage first. Exports only after the capture is finalized.
 * A failed export never deletes the local original.
 */
object Storage {
    @Volatile var password: CharArray? = null

    fun save(activity: MainActivity, file: File, mime: String, prefs: SharedPreferences, status: (String) -> Unit) {
        Thread {
            if (!file.isFile || file.length() == 0L) {
                status("保存失敗: 元ファイルがありません")
                return@Thread
            }
            var hasDestination = false
            val folder = prefs.getString("folder", null)
            if (folder != null) {
                hasDestination = true
                try {
                    val tree = Uri.parse(folder)
                    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    val target = DocumentsContract.createDocument(activity.contentResolver, parent, mime, file.name)
                        ?: error("ドキュメントを作成できません")
                    activity.contentResolver.openOutputStream(target, "w")?.use { out ->
                        FileInputStream(file).use { it.copyTo(out) }
                    } ?: error("保存先に書き込めません")
                    status("フォルダへ保存: ${file.name}")
                } catch (e: Exception) {
                    status("フォルダ保存失敗。ローカルに保持: ${e.message}")
                }
            }
            val base = prefs.getString("smb", null)
            if (!base.isNullOrBlank()) {
                hasDestination = true
                val pass = password?.copyOf()
                if (pass == null) {
                    status("SMB未接続: 設定画面でパスワードを再入力してください。ローカルに保持")
                } else {
                    try {
                        val domain = prefs.getString("domain", "") ?: ""
                        val user = prefs.getString("username", "") ?: ""
                        val auth = NtlmPasswordAuthenticator(domain, user, String(pass))
                        val ctx = SingletonContext.getInstance().withCredentials(auth)
                        val directory = SmbFile(base.trimEnd('/') + "/", ctx)
                        if (!directory.exists()) directory.mkdirs()
                        val remote = SmbFile(directory, file.name)
                        remote.outputStream.use { out -> FileInputStream(file).use { it.copyTo(out) } }
                        status("SMBへ転送完了: ${file.name}")
                    } catch (e: Exception) {
                        status("SMB転送失敗。ローカルに保持: ${e.message}")
                    } finally { pass.fill('\u0000') }
                }
            }
            if (!hasDestination) status("端末内に保存: ${file.name} (アプリ専用保存領域)")
        }.start()
    }
}

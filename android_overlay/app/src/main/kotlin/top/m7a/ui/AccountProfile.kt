package top.m7a.ui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Input: PC March7thAssistant characters.json schema_version=1.
 * No automatic Android scan yet; this file includes equipped items, not all relic inventory.
 */
internal data class ImportedAccount(
    val source: String, val exportedAt: String, val complete: Boolean, val failedCount: Int,
    val characterCount: Int, val warningCount: Int, val promptSummary: String,
)

internal object AccountProfileParser {
    const val MAX_IMPORT_BYTES = 4 * 1024 * 1024
    private const val MAX_PROMPT_CHARS = 20000

    fun parse(raw: String): ImportedAccount {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_BYTES) { "档案文件超过 4 MB" }
        val root = JSONObject(raw)
        require(root.optInt("schema_version", -1) == 1) {
            "仅支持三月七小助手 PC 导出的 characters.json（schema_version 1）"
        }
        val characters = root.optJSONArray("characters") ?: error("档案没有 characters 数组")
        require(characters.length() in 1..300) { "角色列表为空或数量异常" }
        val complete = root.opt("complete") as? Boolean ?: error("档案缺少 complete 标记")
        val failed = root.optJSONArray("failed") ?: JSONArray()
        val exportedAt = root.optString("exported_at").take(60).ifBlank { "未提供" }
        val result = StringBuilder()
        result.append("资料来源：三月七小助手 PC 角色面板导出 schema 1；时间：")
            .append(exportedAt).append("；角色数：").append(characters.length())
            .append("；导出完整：").append(if (complete) "是" else "否")
            .append("；读取失败条目：").append(failed.length()).append("\n")
        result.append("仅包含导出时查看到的角色、已装备光锥和遗器；")
            .append("未装备遗器和完整背包未知，未知不能当作不存在。\n")
        var warnings = 0
        var included = 0
        for (i in 0 until characters.length()) {
            val item = characters.optJSONObject(i) ?: error("角色记录不是对象")
            val name = item.optString("name").trim().take(80)
            val path = item.optString("path").trim().take(40)
            require(name.isNotBlank() && path.isNotBlank()) { "角色姓名或命途缺失" }
            val level = item.optInt("level", -1)
            val maxLevel = item.optInt("max_level", -1)
            val eidolon = item.optInt("eidolon", -1)
            require(level in 1..100 && maxLevel in level..100 && eidolon in 0..6) {
                "角色等级或星魂数据不合法：" + name
            }
            val issueList = item.optJSONArray("warnings") ?: JSONArray()
            warnings += issueList.length()
            val line = StringBuilder().append("- ").append(name).append(" | ").append(path)
                .append(" | ").append(item.optString("element").take(16))
                .append(" | Lv.").append(level).append('/').append(maxLevel)
                .append(" | 星魂 ").append(eidolon)
            val cone = item.optJSONObject("light_cone")
            if (cone == null) {
                line.append(" | 光锥：未装备或未成功读取")
            } else {
                line.append(" | 光锥：").append(cone.optString("name").take(90))
                    .append(" Lv.").append(cone.optInt("level", 0))
                    .append(" 叠影").append(cone.optInt("superimposition", 0))
            }
            val skills = item.optJSONArray("skills")
            if (skills != null && skills.length() > 0) {
                line.append(" | 技能：")
                for (j in 0 until minOf(skills.length(), 9)) {
                    val skill = skills.optJSONObject(j) ?: continue
                    if (j > 0) line.append('，')
                    line.append(skill.optString("name", skill.optString("type")).take(24))
                        .append(' ').append(skill.optInt("level", 0))
                }
            }
            val pieces = item.optJSONObject("relics")?.optJSONArray("pieces")
            line.append(" | 已装备遗器：").append(pieces?.length() ?: 0).append(" 件")
            if (pieces != null) {
                for (j in 0 until minOf(pieces.length(), 6)) {
                    val piece = pieces.optJSONObject(j) ?: continue
                    line.append("；").append(piece.optString("slot").take(20))
                        .append(" ").append(piece.optString("set").take(70))
                        .append(" +").append(piece.optInt("level", 0))
                    piece.optJSONObject("main_stat")?.let { line.append(" 主词条").append(statText(it)) }
                    val subs = piece.optJSONArray("sub_stats")
                    if (subs != null && subs.length() > 0) {
                        line.append(" 副词条(")
                        for (k in 0 until minOf(subs.length(), 4)) {
                            val sub = subs.optJSONObject(k) ?: continue
                            if (k > 0) line.append('，')
                            line.append(statText(sub))
                        }
                        line.append(')')
                    }
                }
            }
            if (issueList.length() > 0) {
                line.append(" | 识别警告：")
                line.append((0 until minOf(issueList.length(), 3))
                    .joinToString("；") { issueList.optString(it).take(70) })
            }
            line.append('\n')
            if (result.length + line.length > MAX_PROMPT_CHARS) {
                result.append("……另有 ").append(characters.length() - included)
                    .append(" 条角色因长度未发送，不代表未拥有。\n")
                break
            }
            result.append(line)
            included++
        }
        if (!complete || failed.length() > 0 || warnings > 0) {
            result.append("注意：导出不完整或存在 OCR 警告（")
                .append(warnings).append(" 条），不可据此断言角色或装备不存在。\n")
        }
        return ImportedAccount("march7th-characters-v1", exportedAt, complete,
            failed.length(), characters.length(), warnings, result.toString())
    }

    private fun statText(stat: JSONObject): String = stat.optString("stat").take(28) +
        "=" + stat.opt("value")?.toString().orEmpty().take(20) +
        if (stat.optBoolean("percent")) "%" else ""

    fun readBounded(stream: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            require(output.size() + count <= MAX_IMPORT_BYTES) { "导入文件过大（最多 4 MB）" }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

/** Encrypted, app-private, local-only account and note storage. */
internal class AccountProfileStore(context: Context) {
    private val directory = context.applicationContext.filesDir
    private val profileFile = AtomicFile(File(directory, "starrail-account-profile.enc"))
    private val notesFile = AtomicFile(File(directory, "starrail-account-notes.enc"))

    fun import(raw: String): ImportedAccount {
        val parsed = AccountProfileParser.parse(raw) // Never overwrite with invalid data.
        encryptedWrite(profileFile, raw)
        return parsed
    }
    fun load(): ImportedAccount? = encryptedRead(profileFile)?.let {
        runCatching { AccountProfileParser.parse(it) }.getOrNull()
    }
    fun loadNotes(): String = encryptedRead(notesFile).orEmpty().take(12000)
    fun saveNotes(notes: String) {
        require(notes.length <= 12000) { "备注超过 12000 字" }
        if (notes.isBlank()) notesFile.delete() else encryptedWrite(notesFile, notes)
    }
    fun clearImported() { profileFile.delete() }
    fun clearNotes() { notesFile.delete() }

    private fun encryptedWrite(target: AtomicFile, text: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key())
        }
        val packed = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val handle = target.startWrite()
        try {
            handle.write(packed)
            target.finishWrite(handle)
        } catch (failure: Exception) {
            target.failWrite(handle)
            throw failure
        }
    }

    private fun encryptedRead(target: AtomicFile): String? {
        if (!target.baseFile.exists()) return null
        return try {
            val data = target.openRead().use { it.readBytes() }
            require(data.size >= 29)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            }
            String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val old = store.getKey(KEY_ALIAS, null)
        if (old != null) return old as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private companion object { const val KEY_ALIAS = "starrail-account-aes" }
}

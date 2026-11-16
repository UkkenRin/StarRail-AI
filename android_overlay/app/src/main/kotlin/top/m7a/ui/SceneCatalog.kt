package top.m7a.ui

import android.content.Context
import org.json.JSONObject

/**
 * Read-only Star Rail scene graph. Generated from M7A v2026.11.17 and bundled with
 * the APK; it contains NO Python scripts, touch coordinates or game control logic.
 *
 * A selected scene is only a user assertion and MUST NOT be treated as a capture result.
 */
internal class SceneCatalog private constructor(
    val upstreamRef: String,
    val scenes: List<Scene>,
) {
    internal data class Profile(val name: String, val destinations: List<String>)
    internal data class Scene(
        val id: String,
        val name: String,
        val pc: Profile?,
        val touch: Profile?,
    ) {
        val labels: List<String>
            get() = listOfNotNull(name, pc?.name, touch?.name).distinct()
    }

    private val byId = scenes.associateBy { it.id }
    val pcCount: Int get() = scenes.count { it.pc != null }
    val touchCount: Int get() = scenes.count { it.touch != null }

    fun get(id: String): Scene? = byId[id]

    fun search(query: String, limit: Int = 12): List<Scene> {
        if (limit < 1) return emptyList()
        val needle = query.trim()
        return scenes.asSequence().filter { scene ->
            needle.isEmpty() || scene.id.contains(needle, ignoreCase = true) ||
                scene.labels.any { it.contains(needle, ignoreCase = true) }
        }.take(limit).toList()
    }

    /** Text that may be passed to an AI only after explicit user submission. */
    fun contextFor(id: String): String {
        val scene = byId[id] ?: return ""
        val lines = mutableListOf(
            "【三月七小助手场景知识（只读参考）】",
            "游戏：崩坏：星穹铁道",
            "用户手动选定的界面：" + scene.name + " (ID " + scene.id + ")；未通过实际游戏画面确认。",
            "来源：March7thAssistant " + upstreamRef + " 的 PC/安卓界面导航图。",
        )
        for ((platform, label) in listOf("pc" to "Windows/PC", "touch" to "Android 触屏")) {
            val profile = if (platform == "pc") scene.pc else scene.touch
            if (profile == null) {
                lines += label + "：该平台未收录此界面，不能假定可操作。"
            } else {
                val names = profile.destinations.mapNotNull { dest ->
                    val target = byId[dest]
                    if (platform == "pc") target?.pc?.name else target?.touch?.name
                }
                lines += label + "：配置名称 " + profile.name +
                    "；已知直接导航目标：" + (if (names.isEmpty()) "无" else names.take(24).joinToString("、"))
            }
        }
        lines += "这些是静态界面知识，不是实时截图或 AI 视觉识别结果；" +
            "不包含可执行点击脚本。AI 不得声称已检测当前屏幕、点击游戏或知道实际敌人、奖励、背包。"
        return lines.joinToString("\n").take(5000)
    }

    companion object {
        private val ID_PATTERN = Regex("^[a-z][a-z0-9_]{0,79}$")
        private const val FILE_NAME = "starrail_ai_scenes.json"

        fun load(context: Context): SceneCatalog = context.assets.open(FILE_NAME).bufferedReader(
            Charsets.UTF_8
        ).use { parse(it.readText()) }

        fun parse(raw: String): SceneCatalog {
            require(raw.length <= 512 * 1024) { "场景知识库异常过大" }
            val root = JSONObject(raw)
            require(root.optInt("schema_version", -1) == 1) { "场景知识库版本不兼容" }
            val ref = root.optJSONObject("source")?.optString("ref").orEmpty()
            require(ref.isNotBlank()) { "缺少场景来源" }
            val records = root.getJSONArray("scenes")
            require(records.length() in 1..300) { "场景数量异常" }
            val scenes = ArrayList<Scene>(records.length())
            val seen = HashSet<String>()
            for (i in 0 until records.length()) {
                val obj = records.getJSONObject(i)
                val id = obj.getString("id")
                require(ID_PATTERN.matches(id) && seen.add(id)) { "非法或重复的场景 ID" }
                val name = obj.getString("name")
                require(name.isNotBlank() && name.length <= 100) { "场景名称异常" }
                fun readProfile(key: String): Profile? {
                    val node = obj.optJSONObject(key) ?: return null
                    val label = node.getString("name")
                    require(label.length in 1..100)
                    val array = node.getJSONArray("destinations")
                    require(array.length() <= 300)
                    val targets = (0 until array.length()).map { array.getString(it) }
                    require(targets.distinct().size == targets.size && targets.all { ID_PATTERN.matches(it) })
                    return Profile(label, targets)
                }
                val pc = readProfile("pc")
                val touch = readProfile("touch")
                require(pc != null || touch != null)
                scenes += Scene(id, name, pc, touch)
            }
            val catalog = SceneCatalog(ref, scenes)
            for (scene in scenes) {
                for ((key, profile) in listOf("pc" to scene.pc, "touch" to scene.touch)) {
                    for (targetId in profile?.destinations.orEmpty()) {
                        val dest = catalog.get(targetId)
                        require(dest != null && (if (key == "pc") dest.pc != null else dest.touch != null)) {
                            "导航目标缺失：" + targetId
                        }
                    }
                }
            }
            return catalog
        }
    }
}

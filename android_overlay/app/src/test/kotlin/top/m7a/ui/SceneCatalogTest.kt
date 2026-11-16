package top.m7a.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JSON + prompt-safety tests; no emulator and no model account required. */
class SceneCatalogTest {
    private val fixture = """
        {
          "schema_version":1,
          "source":{"ref":"v2026.11.17"},
          "scenes":[
            {"id":"main","name":"主界面",
             "pc":{"name":"主界面","destinations":["menu"]},
             "touch":{"name":"主界面","destinations":["menu"]}},
            {"id":"menu","name":"手机菜单",
             "pc":{"name":"手机菜单","destinations":["main"]},
             "touch":{"name":"手机菜单","destinations":["main"]}},
            {"id":"apocalyptic","name":"末日幻影",
             "pc":{"name":"末日幻影","destinations":[]},
             "touch":null}
          ]
        }
    """.trimIndent()

    @Test fun parseBothPlatforms() {
        val catalog = SceneCatalog.parse(fixture)
        assertEquals(3, catalog.scenes.size)
        assertEquals(3, catalog.pcCount)
        assertEquals(2, catalog.touchCount)
        assertEquals("主界面", catalog.get("main")?.name)
        assertEquals("手机菜单", catalog.get("menu")?.touch?.name)
        assertTrue(catalog.search("末日").any { it.id == "apocalyptic" })
    }

    @Test fun contentIsNotRealTimeDetectionOrExecutableCode() {
        val catalog = SceneCatalog.parse(fixture)
        val info = catalog.contextFor("apocalyptic")
        assertTrue(info.contains("用户手动选定"))
        assertTrue(info.contains("未通过实际游戏画面确认"))
        assertTrue(info.contains("Android 触屏：该平台未收录"))
        assertFalse(info.contains("auto.click_element"))
        assertEquals("", catalog.contextFor("missing"))
    }

    @Test fun brokenSchemaAndInvalidTargetsFailClosed() {
        assertTrue(runCatching {
            SceneCatalog.parse(fixture.replace("\"schema_version\":1", "\"schema_version\":8"))
        }.isFailure)
        assertTrue(runCatching {
            SceneCatalog.parse(fixture.replace("\"destinations\":[\"menu\"]",
                "\"destinations\":[\"not_a_real_scene\"]"))
        }.isFailure)
        assertTrue(runCatching {
            SceneCatalog.parse(fixture.replace("\"id\":\"apocalyptic\"", "\"id\":\"main\""))
        }.isFailure)
    }
}

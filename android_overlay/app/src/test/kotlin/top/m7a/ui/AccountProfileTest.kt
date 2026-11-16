package top.m7a.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class AccountProfileTest {
    private val example = """
        {
          "schema_version": 1, "exported_at": "2026-11-17T08:00:00+08:00",
          "complete": true, "failed": [],
          "characters": [
            {
              "name": "三月七", "path": "存护", "element": "冰",
              "level": 80, "max_level": 80, "eidolon": 6,
              "warnings": [],
              "light_cone": {"name": "余生的第一天", "level": 80, "superimposition": 5},
              "skills": [{"name":"战技", "level":10}],
              "relics": {"sets": [{"name":"戍卫风雪的铁卫","pieces":1}],
                "pieces": [
                  {"slot":"头部", "set":"戍卫风雪的铁卫", "level":15,
                   "main_stat":{"stat":"hp","value":705,"percent":false},
                   "sub_stats":[{"stat":"crit_dmg","value":11.7,"percent":true}]}
                ]
              }
            }
          ]
        }
    """.trimIndent()

    @Test fun completeExportProvidesProvenanceAndEquippedItems() {
        val result = AccountProfileParser.parse(example)
        assertEquals("march7th-characters-v1", result.source)
        assertTrue(result.complete)
        assertEquals(1, result.characterCount)
        assertEquals(0, result.failedCount)
        assertTrue(result.promptSummary.contains("三月七"))
        assertTrue(result.promptSummary.contains("余生的第一天"))
        assertTrue(result.promptSummary.contains("戍卫风雪的铁卫"))
        assertTrue(result.promptSummary.contains("crit_dmg=11.7%"))
        assertTrue(result.promptSummary.contains("完整背包未知"))
    }

    @Test fun incompleteExportPreservesWarnings() {
        val partial = example.replace("\"complete\": true, \"failed\": []",
            "\"complete\": false, \"failed\": [{\"name\":\"未知角色\",\"error\":\"识别失败\"}]")
            .replace("\"warnings\": []", "\"warnings\": [\"属性缺失\"]")
        val result = AccountProfileParser.parse(partial)
        assertFalse(result.complete)
        assertEquals(1, result.warningCount)
        assertEquals(1, result.failedCount)
        assertTrue(result.promptSummary.contains("识别失败"))
        assertTrue(result.promptSummary.contains("属性缺失"))
        assertTrue(result.promptSummary.contains("不完整"))
    }

    @Test fun malformedOrUnknownSchemasAreRejected() {
        for (json in listOf(
            example.replace("\"schema_version\": 1", "\"schema_version\": 2"),
            example.replace("\"characters\": [", "\"bad_name\": ["),
            example.replace("\"eidolon\": 6", "\"eidolon\": 8"),
            example.replace("\"complete\": true", "\"complete\": \"yes\""),
            example.replace("\"level\": 80", "\"level\": 101"),
        )) {
            val result = runCatching { AccountProfileParser.parse(json) }
            assertTrue(result.isFailure)
        }
    }

    @Test fun readBoundedLimitsFileSize() {
        assertEquals("abc", AccountProfileParser.readBounded(
            ByteArrayInputStream("abc".toByteArray(Charsets.UTF_8))))
        val tooBig = ByteArray(AccountProfileParser.MAX_IMPORT_BYTES + 1)
        assertTrue(runCatching {
            AccountProfileParser.readBounded(ByteArrayInputStream(tooBig))
        }.isFailure)
    }
}

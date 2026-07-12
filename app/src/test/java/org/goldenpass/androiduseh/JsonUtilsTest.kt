package org.goldenpass.androiduseh

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonUtilsTest {

    @Test
    fun testExtractJson_Robustness() {
        val input = """
            I'm thinking about the task.
            {
                "thought": "I need to click the 'Save' button. It has a } in the description for some reason.",
                "action": "click",
                "x": 500,
                "y": 600
            }
            Followed by some extra text.
        """.trimIndent()
        
        val expected = """
            {
                "thought": "I need to click the 'Save' button. It has a } in the description for some reason.",
                "action": "click",
                "x": 500,
                "y": 600
            }
        """.trimIndent()
        
        val result = JsonUtils.extractJson(input)
        assertEquals(expected, result)
    }

    @Test
    fun testExtractJson_Truncated() {
        val input = """
            {
                "thought": "This is a truncated response
        """.trimIndent()
        
        val expected = """
            {
                "thought": "This is a truncated response
        """.trimIndent()
        
        val result = JsonUtils.extractJson(input)
        assertEquals(expected, result)
    }

    @Test
    fun testExtractJson_NestedBraces() {
        val input = """
            Some text { "outer": { "inner": "value" } } more text
        """.trimIndent()
        
        val expected = """{ "outer": { "inner": "value" } }"""
        
        val result = JsonUtils.extractJson(input)
        assertEquals(expected, result)
    }
}

package org.goldenpass.androiduseh

object JsonUtils {
    /**
     * Extracts a JSON object from a string that may contain other text.
     * It finds the first '{' and the matching closing '}'.
     * It correctly handles nested braces and braces within strings.
     */
    fun extractJson(text: String?): String? {
        if (text == null) return null
        
        val firstBrace = text.indexOf('{')
        if (firstBrace == -1) return text
        
        var braceCount = 0
        var inString = false
        var escape = false
        
        for (i in firstBrace until text.length) {
            val c = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') {
                    braceCount++
                } else if (c == '}') {
                    braceCount--
                    if (braceCount == 0) {
                        return text.substring(firstBrace, i + 1)
                    }
                }
            }
        }
        
        // If we reach here, we didn't find a matching closing brace.
        // It's likely the response was truncated.
        // We return the substring from the first brace to the end to let the JSON parser
        // handle it (and likely throw a specific JSONException).
        return text.substring(firstBrace)
    }
}

package org.goldenpass.androiduseh

object JsonUtils {
    /**
     * Extracts a JSON object from a string that may contain other text.
     * It finds the first '{' and the matching closing '}'.
     * It correctly handles nested braces and braces within strings.
     */
    fun extractJson(text: String?): String? {
        if (text == null) return null
        
        var processedText = text
        // Fix common LLM error: "x":": 100 or "x": : 100
        // This specifically looks for a colon, followed by an optional quote and another colon before a number
        processedText = processedText.replace(Regex(""":\s*"?\s*:\s*(-?\d+\.?\d*)"""), ": $1")
        
        // Fix trailing commas in objects or arrays
        processedText = processedText.replace(Regex(""",\s*([]}])"""), "$1")
        
        val firstBrace = processedText.indexOf('{')
        if (firstBrace == -1) return processedText
        
        var braceCount = 0
        var inString = false
        var escape = false
        
        for (i in firstBrace until processedText.length) {
            val c = processedText[i]
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
                        return processedText.substring(firstBrace, i + 1)
                    }
                }
            }
        }
        
        // If we reach here, we didn't find a matching closing brace.
        // It's likely the response was truncated.
        var fixedJson = processedText.substring(firstBrace).trim()
        
        // Handle trailing backslash escape
        var lastEscape = false
        var lastInString = false
        for (c in fixedJson) {
            if (lastEscape) { lastEscape = false; continue }
            if (c == '\\') { lastEscape = true; continue }
            if (c == '"') { lastInString = !lastInString; continue }
        }
        
        if (lastEscape) {
            fixedJson = fixedJson.substring(0, fixedJson.length - 1).trim()
        }
        
        if (lastInString) {
            fixedJson = fixedJson.trimEnd() + "\""
        }
        
        // Remove trailing colon or comma which often appears before a truncated block
        while (fixedJson.endsWith(",") || fixedJson.endsWith(":") || fixedJson.endsWith("{") || fixedJson.endsWith("[")) {
            fixedJson = fixedJson.substring(0, fixedJson.length - 1).trim()
        }
        
        // Final pass to ensure all strings are closed and braces are balanced
        // This is a simple but effective way to handle most truncation issues.
        var result = ""
        var currentInString = false
        var currentEscape = false
        for (c in fixedJson) {
            if (currentEscape) { result += c; currentEscape = false; continue }
            if (c == '\\') { result += c; currentEscape = true; continue }
            if (c == '"') { currentInString = !currentInString; result += c; continue }
            if ((currentInString) && (c == '\n')) { result += " "; continue } // Replace newline in string
            result += c
        }
        if (currentInString) result += "\""
        
        var finalBraceCount = 0
        for (c in result) {
            if (c == '{') finalBraceCount++
            else if (c == '}') finalBraceCount--
        }
        while (finalBraceCount > 0) {
            result += "}"
            finalBraceCount--
        }
        
        return result
    }
}

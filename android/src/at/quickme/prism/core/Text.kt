// Showing text from elsewhere (an approval, a request) without hiding
// anything: control, invisible and bidi characters are escaped (as the Mac
// app does).

package at.quickme.prism.core

object Text {
    /** Control characters, and format characters that change how text looks without showing. */
    @JvmStatic
    fun needsEscape(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
        Character.UNASSIGNED, Character.PRIVATE_USE, Character.SURROGATE -> true
        else -> false
    }

    /**
     * Untrusted text, safe to show: nothing in it is invisible or reorders
     * what is around it. [lines] keeps line breaks and tabs (code).
     */
    @JvmStatic
    @JvmOverloads
    fun visible(text: String, lines: Boolean = false): String {
        val out = StringBuilder()
        text.codePoints().forEach { cp ->
            if (lines && (cp == '\n'.code || cp == '\t'.code)) out.appendCodePoint(cp)
            else if (needsEscape(cp)) out.append(if (cp <= 0xFF) String.format("\\x%02x", cp) else String.format("\\u{%x}", cp))
            else out.appendCodePoint(cp)
        }
        return out.toString()
    }
}

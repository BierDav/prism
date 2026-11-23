// Showing text that came from elsewhere (approvals, alerts, the server's
// errors) without letting it hide anything: control, invisible and bidi
// characters are shown as escapes.

import Foundation

/// Control characters, and format characters (bidi overrides, zero-width
/// joiners and the like) that change how text looks without showing.
private func needsEscape(_ scalar: Unicode.Scalar) -> Bool {
    switch scalar.properties.generalCategory {
    case .control, .format, .lineSeparator, .paragraphSeparator, .unassigned, .privateUse, .surrogate:
        return true
    default:
        return false
    }
}

/// Makes untrusted text safe to display on one line.
func visible(_ text: String) -> String {
    var out = ""
    for scalar in text.unicodeScalars {
        if needsEscape(scalar) {
            out += scalar.value <= 0xFF ? String(format: "\\x%02x", scalar.value) : String(format: "\\u{%x}", scalar.value)
        } else {
            out.unicodeScalars.append(scalar)
        }
    }
    return out
}

/// Like `visible`, but keeps the line breaks: for command output and summaries.
func visibleLines(_ text: String) -> String {
    text.replacingOccurrences(of: "\r\n", with: "\n").split(separator: "\n", omittingEmptySubsequences: false)
        .map { visible(String($0)) }.joined(separator: "\n")
}

/// `visible`, cut to `limit` characters with an ellipsis: for notifications.
func visibleShort(_ text: String, _ limit: Int = 80) -> String {
    let flat = visible(text)
    return flat.count <= limit ? flat : String(flat.prefix(limit - 1)) + "…"
}

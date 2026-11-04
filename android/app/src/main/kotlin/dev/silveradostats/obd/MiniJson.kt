package dev.silveradostats.obd

/**
 * Minimal JSON reader for the profile (no dependencies, no reflection, pure JVM).
 *
 * Objects become LinkedHashMap<String, Any?> (key order kept), arrays ArrayList<Any?>, numbers
 * Long when written without '.', 'e' or 'E' (Python ints) and Double otherwise (Python floats),
 * plus String, Boolean and null. Python's NaN / Infinity / -Infinity tokens are accepted.
 */
internal object MiniJson {
  fun parse(text: String): Any? {
    val p = Parser(text)
    p.ws()
    val v = p.value(0)
    p.ws()
    if (p.i != text.length) p.fail("trailing data")
    return v
  }

  private class Parser(val s: String) {
    var i = 0

    fun fail(what: String): Nothing = throw IllegalArgumentException("JSON: $what at offset $i")

    fun ws() {
      while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
    }

    fun value(depth: Int): Any? {
      if (depth > 64) fail("nesting too deep")
      if (i >= s.length) fail("unexpected end")
      return when (s[i]) {
        '{' -> obj(depth)
        '[' -> arr(depth)
        '"' -> str()
        't' -> lit("true", true)
        'f' -> lit("false", false)
        'n' -> lit("null", null)
        'N' -> lit("NaN", Double.NaN)
        'I' -> lit("Infinity", Double.POSITIVE_INFINITY)
        else -> if (s.startsWith("-Infinity", i)) lit("-Infinity", Double.NEGATIVE_INFINITY) else num()
      }
    }

    fun lit(word: String, v: Any?): Any? {
      if (!s.startsWith(word, i)) fail("bad literal")
      i += word.length
      return v
    }

    fun obj(depth: Int): Map<String, Any?> {
      val out = LinkedHashMap<String, Any?>()
      i++
      ws()
      if (i < s.length && s[i] == '}') {
        i++
        return out
      }
      while (true) {
        ws()
        if (i >= s.length || s[i] != '"') fail("expected key")
        val k = str()
        ws()
        if (i >= s.length || s[i] != ':') fail("expected ':'")
        i++
        ws()
        out[k] = value(depth + 1)
        ws()
        if (i >= s.length) fail("unexpected end")
        when (s[i++]) {
          ',' -> continue
          '}' -> return out
          else -> fail("expected ',' or '}'")
        }
      }
    }

    fun arr(depth: Int): List<Any?> {
      val out = ArrayList<Any?>()
      i++
      ws()
      if (i < s.length && s[i] == ']') {
        i++
        return out
      }
      while (true) {
        ws()
        out += value(depth + 1)
        ws()
        if (i >= s.length) fail("unexpected end")
        when (s[i++]) {
          ',' -> continue
          ']' -> return out
          else -> fail("expected ',' or ']'")
        }
      }
    }

    fun str(): String {
      val sb = StringBuilder()
      i++
      while (true) {
        if (i >= s.length) fail("unterminated string")
        val c = s[i++]
        when {
          c == '"' -> return sb.toString()
          c == '\\' -> {
            if (i >= s.length) fail("bad escape")
            when (val e = s[i++]) {
              '"' -> sb.append('"')
              '\\' -> sb.append('\\')
              '/' -> sb.append('/')
              'b' -> sb.append('\b')
              'f' -> sb.append('\u000C')
              'n' -> sb.append('\n')
              'r' -> sb.append('\r')
              't' -> sb.append('\t')
              'u' -> {
                if (i + 4 > s.length) fail("bad \\u escape")
                val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("bad \\u escape")
                sb.append(code.toChar())
                i += 4
              }
              else -> fail("bad escape '$e'")
            }
          }
          c < ' ' -> fail("control character in string")
          else -> sb.append(c)
        }
      }
    }

    fun num(): Any {
      val start = i
      if (i < s.length && s[i] == '-') i++
      while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
      val t = s.substring(start, i)
      if (t.isEmpty() || t == "-") fail("unexpected character")
      if (!NUMBER.matches(t)) fail("bad number '$t'")
      if (t.none { it == '.' || it == 'e' || it == 'E' }) t.toLongOrNull()?.let { return it }
      return t.toDoubleOrNull() ?: fail("bad number '$t'")
    }
  }

  private val NUMBER = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?$")
}

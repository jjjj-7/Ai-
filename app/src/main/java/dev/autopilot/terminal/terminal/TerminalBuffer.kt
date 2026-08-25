package dev.autopilot.terminal.terminal

data class Span(
    val start: Int,
    val end: Int,
    val fg: Int? = null,
    val bg: Int? = null,
    val bold: Boolean = false,
    val underline: Boolean = false
)

data class StyledLine(val text: String, val spans: List<Span>)

class TerminalBuffer(private val maxCols: Int = 500, private val maxLines: Int = 4000) {

    private enum class State { GROUND, ESC, CSI }

    private class MutableSpan(var start: Int) {
        var end: Int = start
        var fg: Int? = null
        var bg: Int? = null
        var bold: Boolean = false
        var underline: Boolean = false
        fun snapshot(textLen: Int): Span {
            val e = end.coerceAtMost(textLen)
            return Span(start.coerceAtMost(e), e, fg, bg, bold, underline)
        }
    }

    private var state = State.GROUND
    private val csiParams = StringBuilder()
    private val sb = StringBuilder()
    private val pendingSpans = mutableListOf<MutableSpan>()
    private val lines = ArrayDeque<StyledLine>()

    private var curFg: Int? = null
    private var curBg: Int? = null
    private var curBold = false
    private var curUnderline = false

    @Volatile var version: Long = 0
        private set

    fun snapshot(): List<StyledLine> = synchronized(lines) { lines.toList() + StyledLine(sb.toString(), currentSpans()) }

    fun process(data: ByteArray) {
        synchronized(this) {
            for (b in data) processByte(b)
            version++
        }
    }

    private fun processByte(b: Byte) {
        when (state) {
            State.GROUND -> groundByte(b)
            State.ESC -> state = if (b == '['.code.toByte()) State.CSI else State.GROUND
            State.CSI -> csiByte(b)
        }
    }

    private fun groundByte(b: Byte) {
        when (b.toInt()) {
            0x1B -> state = State.ESC
            '\n'.code -> commitLine()
            '\r'.code -> carriageReturn()
            '\b'.code -> backspace()
            '\t'.code -> repeat(4) { appendCh(' ') }
            else -> if (b >= ' '.code.toByte() && sb.length < maxCols) appendCh(b.toInt().toChar())
        }
    }

    private fun csiByte(b: Byte) {
        val c = b.toInt() and 0xFF
        if (c in 0x30..0x3F) {
            csiParams.append(c.toChar())
            if (csiParams.length > 32) { csiParams.setLength(0); state = State.GROUND }
            return
        }
        if (c in 0x40..0x7E) {
            dispatchCsi(c.toChar(), csiParams.toString())
            csiParams.setLength(0)
            state = State.GROUND
        }
    }

    private fun dispatchCsi(cmd: Char, raw: String) {
        val parts = raw.split(';').map { it.toIntOrNull() ?: 0 }
        when (cmd) {
            'm' -> applySgr(parts)
            'J' -> if (parts.getOrNull(0) == 2 || parts.getOrNull(0) == 3) clearScreen()
            'K' -> Unit
            'D' -> backspaceN(parts.getOrElse(0) { 1 })
            else -> Unit
        }
    }

    private fun applySgr(parts: List<Int>) {
        if (parts.isEmpty()) { resetAttrs(); return }
        var i = 0
        while (i < parts.size) {
            when (val p = parts[i]) {
                0 -> resetAttrs()
                1 -> curBold = true
                4 -> curUnderline = true
                22 -> curBold = false
                24 -> curUnderline = false
                in 30..37 -> curFg = p - 30
                39 -> curFg = null
                in 40..47 -> curBg = p - 40
                49 -> curBg = null
                in 90..97 -> curFg = p - 90 + 8
                in 100..107 -> curBg = p - 100 + 8
                38, 48 -> i = applyExtendedColor(parts, i)
            }
            i++
        }
    }

    private fun applyExtendedColor(parts: List<Int>, i: Int): Int {
        val isFg = parts[i] == 38
        if (i + 2 < parts.size && parts[i + 1] == 5) {
            setColor(isFg, parts[i + 2])
            return i + 2
        }
        if (i + 4 < parts.size && parts[i + 1] == 2) {
            val rgb = (parts[i + 2] shl 16) or (parts[i + 3] shl 8) or parts[i + 4] or 0x1000000
            setColor(isFg, rgb)
            return i + 4
        }
        return i
    }

    private fun setColor(isFg: Boolean, color: Int?) {
        if (isFg) curFg = color else curBg = color
    }

    private fun resetAttrs() {
        curFg = null; curBg = null; curBold = false; curUnderline = false
    }

    private fun clearScreen() {
        synchronized(lines) { lines.clear() }
        carriageReturn()
    }

    private fun backspace() {
        if (sb.isNotEmpty()) {
            sb.deleteCharAt(sb.length - 1)
            pendingSpans.lastOrNull()?.let { if (it.end > sb.length) it.end = sb.length }
        }
    }

    private fun backspaceN(n: Int) = repeat(n.coerceAtMost(50)) { backspace() }

    private fun carriageReturn() {
        closeOpenSpan()
        sb.setLength(0)
        pendingSpans.clear()
    }

    private fun appendCh(c: Char) {
        val last = pendingSpans.lastOrNull()
        val matches = last != null &&
            last.fg == curFg && last.bg == curBg && last.bold == curBold && last.underline == curUnderline
        if (!matches) {
            closeOpenSpan()
            pendingSpans.add(MutableSpan(sb.length).apply {
                end = sb.length
                fg = curFg; bg = curBg; bold = curBold; underline = curUnderline
            })
        }
        val span = pendingSpans.last()
        sb.append(c)
        span.end = sb.length
    }

    private fun closeOpenSpan() {
        val last = pendingSpans.lastOrNull() ?: return
        last.end = sb.length
        if (last.end <= last.start) pendingSpans.removeAt(pendingSpans.size - 1)
    }

    private fun commitLine() {
        closeOpenSpan()
        synchronized(lines) {
            lines.addLast(StyledLine(sb.toString(), pendingSpans.map { it.snapshot(sb.length) }))
            while (lines.size > maxLines) lines.removeFirst()
        }
        sb.setLength(0)
        pendingSpans.clear()
    }

    private fun currentSpans(): List<Span> = pendingSpans.map { it.snapshot(sb.length) }
}

/*
 * Copyright 2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlinx.serialization.json

import kotlinx.serialization.*
import kotlinx.serialization.internal.*
import kotlin.reflect.*

data class JSON(
        private val unquoted: Boolean = false,
        private val indented: Boolean = false,
        private val indent: String = "    ",
        internal val nonstrict: Boolean = false,
        val updateMode: UpdateMode = UpdateMode.OVERWRITE,
        val context: SerialContext? = null
) {
    fun <T> stringify(saver: KSerialSaver<T>, obj: T): String {
        val sb = StringBuilder()
        val output = JsonOutput(Mode.OBJ, Composer(sb))
        output.write(saver, obj)
        return sb.toString()
    }

    inline fun <reified T : Any> stringify(obj: T): String = stringify(context.klassSerializer(T::class), obj)

    fun <T> parse(loader: KSerialLoader<T>, str: String): T {
        val parser = Parser(str)
        val input = JsonInput(Mode.OBJ, parser)
        val result = input.read(loader)
        check(parser.tc == TC_EOF) { "Shall parse complete string"}
        return result
    }

    inline fun <reified T : Any> parse(str: String): T = parse(context.klassSerializer(T::class), str)

    companion object {
        fun <T> stringify(saver: KSerialSaver<T>, obj: T): String = plain.stringify(saver, obj)
        inline fun <reified T : Any> stringify(obj: T): String = stringify(T::class.serializer(), obj)
        fun <T> parse(loader: KSerialLoader<T>, str: String): T = plain.parse(loader, str)
        inline fun <reified T : Any> parse(str: String): T = parse(T::class.serializer(), str)

        val plain = JSON()
        val unquoted = JSON(unquoted = true)
        val indented = JSON(indented = true)
        val nonstrict = JSON(nonstrict = true)
    }

    private inner class JsonOutput(val mode: Mode, val w: Composer) : ElementValueOutput() {
        init {
            context = this@JSON.context
        }

        private var forceStr: Boolean = false

        override fun writeBegin(desc: KSerialClassDesc, vararg typeParams: KSerializer<*>): KOutput {
            val newMode = switchMode(mode, desc, typeParams)
            if (newMode.begin != INVALID) {
                w.print(newMode.begin)
                w.indent()
            }
            return if (mode == newMode) this else JsonOutput(newMode, w) // todo: reuse instance per mode
        }

        override fun writeEnd(desc: KSerialClassDesc) {
            if (mode.end != INVALID) {
                w.unIndent()
                w.nextItem()
                w.print(mode.end)
            }
        }

        override fun writeElement(desc: KSerialClassDesc, index: Int): Boolean {
            when (mode) {
                Mode.LIST, Mode.MAP -> {
                    if (index == 0) return false
                    if (index > 1)
                        w.print(COMMA)
                    w.nextItem()
                }
                Mode.ENTRY, Mode.POLY -> {
                    if (index == 0)
                        forceStr = true
                    if (index == 1) {
                        w.print(if (mode == Mode.ENTRY) COLON else COMMA)
                        w.space()
                        forceStr = false
                    }
                }
                else -> {
                    if (index > 0)
                        w.print(COMMA)
                    w.nextItem()
                    writeStringValue(desc.getElementName(index))
                    w.print(COLON)
                    w.space()
                }
            }
            return true
        }

        override fun writeNullValue() {
            w.print(NULL)
        }

        override fun writeBooleanValue(value: Boolean) { if (forceStr) writeStringValue(value.toString()) else w.print(value) }
        override fun writeByteValue(value: Byte) { if (forceStr) writeStringValue(value.toString()) else w.print(value) }
        override fun writeShortValue(value: Short) { if (forceStr) writeStringValue(value.toString()) else w.print(value) }
        override fun writeIntValue(value: Int) { if (forceStr) writeStringValue(value.toString()) else w.print(value) }
        override fun writeLongValue(value: Long) { if (forceStr) writeStringValue(value.toString()) else w.print(value) }

        override fun writeFloatValue(value: Float) {
            if (forceStr || !value.isFinite()) writeStringValue(value.toString()) else
                w.print(value)
        }

        override fun writeDoubleValue(value: Double) {
            if (forceStr || !value.isFinite()) writeStringValue(value.toString()) else
                w.print(value)
        }

        override fun writeCharValue(value: Char) {
            writeStringValue(value.toString())
        }

        override fun writeStringValue(value: String) {
            if (unquoted && !mustBeQuoted(value)) {
                w.print(value)
            } else {
                w.printQuoted(value)
            }
        }

        override fun writeNonSerializableValue(value: Any) {
            writeStringValue(value.toString())
        }
    }

    private inner class Composer(val sb: StringBuilder) {
        var level = 0
        fun indent() { level++ }
        fun unIndent() { level-- }

        fun nextItem() {
            if (indented) {
                println()
                repeat(level) { print(indent) }
            }
        }

        fun space() {
            if (indented)
                print(' ')
        }

        fun print(v: Char) = sb.append(v)
        fun print(v: String) = sb.append(v)

        fun print(v: Float) = sb.append(v)
        fun print(v: Double) = sb.append(v)
        fun print(v: Byte) = sb.append(v)
        fun print(v: Short) = sb.append(v)
        fun print(v: Int) = sb.append(v)
        fun print(v: Long) = sb.append(v)
        fun print(v: Boolean) = sb.append(v)

        fun printUnicodeEscape(c: Int): Unit = with(sb) {
            append(STRING_ESC)
            append(UNICODE_ESC)
            append(toHexChar(c shr 12))
            append(toHexChar(c shr 8))
            append(toHexChar(c shr 4))
            append(toHexChar(c))
        }

        fun printQuoted(value: String): Unit = with(sb) {
            append(STRING)
            var lastPos = 0
            for (i in 0 until value.length) {
                val c = value[i].toInt()
                if (c >= C2ESC_MAX) continue // no need to escape
                val esc = C2ESC[c].toChar()
                if (esc == INVALID) continue // no need to escape
                append(value, lastPos, i) // flush prev
                lastPos = i + 1
                when (esc) {
                    UNICODE_ESC -> printUnicodeEscape(c)
                    else -> {
                        append(STRING_ESC)
                        append(esc)
                    }
                }
            }
            append(value, lastPos, value.length)
            append(STRING)
        }
    }

    private inner class JsonInput(val mode: Mode, val p: Parser) : ElementValueInput() {
        var curIndex = 0
        var entryIndex = 0

        init {
            context = this@JSON.context
        }

        override val updateMode: UpdateMode
            get() = this@JSON.updateMode

        override fun readBegin(desc: KSerialClassDesc, vararg typeParams: KSerializer<*>): KInput {
            val newMode = switchMode(mode, desc, typeParams)
            if (newMode.begin != INVALID) {
                require(p.tc == newMode.beginTc, p.tokenPos) { "Expected '${newMode.begin}, kind: ${desc.kind}'" }
                p.nextToken()
            }
            return when (newMode) {
                Mode.LIST, Mode.MAP, Mode.POLY -> JsonInput(newMode, p) // need fresh cur index
                else -> if (mode == newMode) this else
                    JsonInput(newMode, p) // todo: reuse instance per mode
            }
        }

        override fun readEnd(desc: KSerialClassDesc) {
            if (mode.end != INVALID) {
                require(p.tc == mode.endTc, p.tokenPos) { "Expected '${mode.end}'" }
                p.nextToken()
            }
        }

        override fun readNotNullMark(): Boolean {
            return p.tc != TC_NULL
        }

        override fun readNullValue(): Nothing? {
            require(p.tc == TC_NULL, p.tokenPos) { "Expected 'null' literal" }
            p.nextToken()
            return null
        }

        override fun readElement(desc: KSerialClassDesc): Int {
            while (true) {
                if (p.tc == TC_COMMA) p.nextToken()
                when (mode) {
                    Mode.LIST, Mode.MAP -> {
                        return if (!p.canBeginValue) READ_DONE else ++curIndex
                    }
                    Mode.POLY -> {
                        return when (entryIndex++) {
                            0 -> 0
                            1 -> 1
                            else -> {
                                entryIndex = 0
                                READ_DONE
                            }
                        }
                    }
                    Mode.ENTRY -> {
                        return when (entryIndex++) {
                            0 -> 0
                            1 -> {
                                require(p.tc == TC_COLON, p.tokenPos) { "Expected ':'" }
                                p.nextToken()
                                1
                            }
                            else -> {
                                entryIndex = 0
                                READ_DONE
                            }
                        }
                    }
                    else -> {
                        if (!p.canBeginValue) return READ_DONE
                        val key = p.takeStr()
                        require(p.tc == TC_COLON, p.tokenPos) { "Expected ':'" }
                        p.nextToken()
                        val ind = desc.getElementIndex(key)
                        if (ind != UNKNOWN_NAME)
                            return ind
                        if (!nonstrict)
                            throw SerializationException("Strict JSON encountered unknown key: $key")
                        else
                            p.skipElement()
                    }
                }
            }
        }

        override fun readBooleanValue(): Boolean = p.takeStr() == "true" // KT-16348
        override fun readByteValue(): Byte = p.takeStr().toByte()
        override fun readShortValue(): Short = p.takeStr().toShort()
        override fun readIntValue(): Int = p.takeStr().toInt()
        override fun readLongValue(): Long = p.takeStr().toLong()
        override fun readFloatValue(): Float = p.takeStr().toFloat()
        override fun readDoubleValue(): Double = p.takeStr().toDouble()
        override fun readCharValue(): Char = p.takeStr().single()
        override fun readStringValue(): String = p.takeStr()

        override fun <T : Enum<T>> readEnumValue(enumClass: KClass<T>): T = enumFromName(enumClass, p.takeStr())
    }

    private class Parser(val source: String) {
        var curPos: Int = 0 // position in source

        // updated by nextToken
        var tokenPos: Int = 0
        var tc: Byte = TC_EOF

        // update by nextString/nextLiteral
        var offset = -1 // when offset >= 0 string is in source, otherwise in buf
        var length = 0 // length of string
        var buf = CharArray(16) // only used for strings with escapes

        init {
            nextToken()
        }

        val canBeginValue: Boolean get() = when (tc) {
            TC_BEGIN_LIST, TC_BEGIN_OBJ, TC_OTHER, TC_STRING, TC_NULL -> true
            else -> false
        }

        fun takeStr(): String {
            if (tc != TC_OTHER && tc != TC_STRING) fail(tokenPos, "Expected string or non-null literal")
            val prevStr = if (offset < 0)
                buf.createString(length) else
                source.substring(offset, offset + length)
            nextToken()
            return prevStr
        }

        fun append(ch: Char) {
            if (length >= buf.size) buf = buf.copyOf(2 * buf.size)
            buf[length++] = ch
        }

        // initializes buf usage upon the first encountered escaped char
        fun appendRange(source: String, fromIndex: Int, toIndex: Int) {
            val addLen = toIndex - fromIndex
            val oldLen = length
            val newLen = oldLen + addLen
            if (newLen > buf.size) buf = buf.copyOf(newLen.coerceAtLeast(2 * buf.size))
            for (i in 0 until addLen) buf[oldLen + i] = source[fromIndex + i]
            length += addLen
        }

        fun nextToken() {
            val source = source
            var curPos = curPos
            while(true) {
                if (curPos >= source.length) {
                    tokenPos = curPos
                    tc = TC_EOF
                    return
                }
                val ch = source[curPos]
                val tc = c2tc(ch)
                when (tc) {
                    TC_WS -> curPos++ // skip whitespace
                    TC_OTHER -> {
                        nextLiteral(source, curPos)
                        return
                    }
                    TC_STRING -> {
                        nextString(source, curPos)
                        return
                    }
                    else -> {
                        tokenPos = curPos
                        this.tc = tc
                        this.curPos = curPos + 1
                        return
                    }
                }
            }
        }

        fun nextLiteral(source: String, startPos: Int) {
            tokenPos = startPos
            offset = startPos
            var curPos = startPos
            while(true) {
                curPos++
                if (curPos >= source.length || c2tc(source[curPos]) != TC_OTHER) break
            }
            this.curPos = curPos
            length = curPos - offset
            tc = if (rangeEquals(source, offset, length, NULL)) TC_NULL else TC_OTHER
        }

        fun nextString(source: String, startPos: Int) {
            tokenPos = startPos
            length = 0 // in buffer
            var curPos = startPos + 1
            var lastPos = curPos
            parse@ while(true) {
                if (curPos >= source.length) fail(curPos, "Unexpected end in string")
                when (source[curPos]) {
                    STRING -> break@parse
                    STRING_ESC -> {
                        appendRange(source, lastPos, curPos)
                        val newPos = appendEsc(source, curPos + 1)
                        curPos = newPos
                        lastPos = newPos
                    }
                    else -> curPos++
                }
            }
            if (lastPos == startPos + 1) {
                // there was no escaped chars
                this.offset = lastPos
                this.length = curPos - lastPos
            } else {
                // some escaped chars were there
                appendRange(source, lastPos, curPos)
                this.offset = -1
            }
            this.curPos = curPos + 1
            tc = TC_STRING
        }

        fun appendEsc(source: String, startPos: Int): Int {
            var curPos = startPos
            require(curPos < source.length, curPos) { "Unexpected end after escape char" }
            val curChar = source[curPos++]
            if (curChar == UNICODE_ESC) {
                curPos = appendHex(source, curPos)
            } else {
                val c = esc2c(curChar.toInt())
                require(c != INVALID, curPos) { "Invalid escaped char '$curChar'" }
                append(c)
            }
            return curPos
        }

        fun appendHex(source: String, startPos: Int): Int {
            var curPos = startPos
            append(((fromHexChar(source, curPos++) shl 12) +
                (fromHexChar(source, curPos++) shl 8) +
                (fromHexChar(source, curPos++) shl 4) +
                fromHexChar(source, curPos++)).toChar())
            return curPos
        }

        fun skipElement() {
            if (tc != TC_BEGIN_OBJ && tc != TC_BEGIN_LIST) {
                nextToken()
                return
            }
            val tokenStack = mutableListOf<Byte>()
            do {
                when (tc) {
                    TC_BEGIN_LIST, TC_BEGIN_OBJ -> tokenStack.add(tc)
                    TC_END_LIST -> {
                        if (tokenStack.last() != TC_BEGIN_LIST) throw SerializationException("Invalid JSON at $curPos: found ] instead of }")
                        tokenStack.removeAt(tokenStack.size - 1)
                    }
                    TC_END_OBJ -> {
                        if (tokenStack.last() != TC_BEGIN_OBJ) throw SerializationException("Invalid JSON at $curPos: found } instead of ]")
                        tokenStack.removeAt(tokenStack.size - 1)
                    }
                }
                nextToken()
            } while (tokenStack.isNotEmpty())
        }
    }
}

// ----------- JSON utilities -----------

private enum class Mode(val begin: Char, val end: Char) {
    OBJ(BEGIN_OBJ, END_OBJ),
    LIST(BEGIN_LIST, END_LIST),
    MAP(BEGIN_OBJ, END_OBJ),
    POLY(BEGIN_LIST, END_LIST),
    ENTRY(INVALID, INVALID);

    val beginTc: Byte = c2tc(begin)
    val endTc: Byte = c2tc(end)
}

private fun switchMode(mode: Mode, desc: KSerialClassDesc, typeParams: Array<out KSerializer<*>>): Mode =
    when (desc.kind) {
        KSerialClassKind.POLYMORPHIC -> Mode.POLY
        KSerialClassKind.LIST, KSerialClassKind.SET -> Mode.LIST
        KSerialClassKind.MAP -> {
            val keyKind = typeParams[0].serialClassDesc.kind
            if (keyKind == KSerialClassKind.PRIMITIVE || keyKind == KSerialClassKind.ENUM)
                Mode.MAP
            else Mode.LIST
        }
        KSerialClassKind.ENTRY -> if (mode == Mode.MAP) Mode.ENTRY else Mode.OBJ
        else -> Mode.OBJ
    }


private fun require(condition: Boolean, pos: Int, msg: () -> String ) {
    if (!condition)
        fail(pos, msg())
}

private fun fail(pos: Int, msg: String): Nothing {
    throw IllegalArgumentException("JSON at $pos: $msg")
}

// special strings
private const val NULL = "null"

// special chars
private const val COMMA = ','
private const val COLON = ':'
private const val BEGIN_OBJ = '{'
private const val END_OBJ = '}'
private const val BEGIN_LIST = '['
private const val END_LIST = ']'
private const val STRING = '"'
private const val STRING_ESC = '\\'

private const val INVALID = 0.toChar()
private const val UNICODE_ESC = 'u'

// token classes
private const val TC_OTHER: Byte = 0
private const val TC_STRING: Byte = 1
private const val TC_STRING_ESC: Byte = 2
private const val TC_WS: Byte = 3
private const val TC_COMMA: Byte = 4
private const val TC_COLON: Byte = 5
private const val TC_BEGIN_OBJ: Byte = 6
private const val TC_END_OBJ: Byte = 7
private const val TC_BEGIN_LIST: Byte = 8
private const val TC_END_LIST: Byte = 9
private const val TC_NULL: Byte = 10
private const val TC_INVALID: Byte = 11
private const val TC_EOF: Byte = 12

// mapping from chars to token classes
private const val CTC_MAX = 0x7e

private val C2TC = ByteArray(CTC_MAX).apply {
    for (i in 0..0x20)
        initC2TC(i, TC_INVALID)
    initC2TC(0x09, TC_WS)
    initC2TC(0x0a, TC_WS)
    initC2TC(0x0d, TC_WS)
    initC2TC(0x20, TC_WS)
    initC2TC(COMMA, TC_COMMA)
    initC2TC(COLON, TC_COLON)
    initC2TC(BEGIN_OBJ, TC_BEGIN_OBJ)
    initC2TC(END_OBJ, TC_END_OBJ)
    initC2TC(BEGIN_LIST, TC_BEGIN_LIST)
    initC2TC(END_LIST, TC_END_LIST)
    initC2TC(STRING, TC_STRING)
    initC2TC(STRING_ESC, TC_STRING_ESC)
}

private fun ByteArray.initC2TC(c : Int, cl: Byte) { this[c] = cl }

private fun ByteArray.initC2TC(c: Char, cl: Byte) {
    initC2TC(c.toInt(), cl)
}

private fun c2tc(c: Char) = if (c.toInt() < CTC_MAX) C2TC[c.toInt()] else TC_OTHER

private fun mustBeQuoted(str: String): Boolean {
    if (str == NULL) return true
    for (ch in str) {
        if (c2tc(ch) != TC_OTHER) return true
    }
    return false
}

// mapping from chars to their escape chars and back
private const val C2ESC_MAX = 0x5d
private const val ESC2C_MAX = 0x75

private val ESC2C = ByteArray(ESC2C_MAX)

private val C2ESC = ByteArray(C2ESC_MAX).apply {
    for (i in 0x00..0x1f)
        initC2ESC(i, UNICODE_ESC)
    initC2ESC(0x08, 'b')
    initC2ESC(0x09, 't')
    initC2ESC(0x0a, 'n')
    initC2ESC(0x0c, 'f')
    initC2ESC(0x0d, 'r')
    initC2ESC('/', '/')
    initC2ESC(STRING, STRING)
    initC2ESC(STRING_ESC, STRING_ESC)
}

private fun ByteArray.initC2ESC(c: Int, esc: Char) {
    this[c] = esc.toByte()
    if (esc != UNICODE_ESC) ESC2C[esc.toInt()] = c.toByte()
}

private fun ByteArray.initC2ESC(c: Char, esc: Char) {
    initC2ESC(c.toInt(), esc)
}

private fun esc2c(c: Int): Char = if (c < ESC2C_MAX) ESC2C[c].toChar() else INVALID

private fun toHexChar(i: Int) : Char {
    val d = i and 0xf
    return if (d < 10) (d + '0'.toInt()).toChar()
    else (d - 10 + 'a'.toInt()).toChar()
}

private fun fromHexChar(source: String, curPos: Int): Int {
    require(curPos < source.length, curPos) { "Unexpected end in unicode escape" }
    val curChar = source[curPos]
    return when (curChar) {
        in '0'..'9' -> curChar.toInt() - '0'.toInt()
        in 'a'..'f' -> curChar.toInt() - 'a'.toInt() + 10
        in 'A'..'F' -> curChar.toInt() - 'A'.toInt() + 10
        else -> throw fail(curPos, "Invalid toHexChar char '$curChar' in unicode escape")
    }
}

private fun rangeEquals(source: String, start: Int, length: Int, str: String): Boolean {
    val n = str.length
    if (length != n) return false
    for (i in 0 until n) if (source[start + i] != str[i]) return false
    return true
}

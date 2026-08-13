package io.github.bmcsilva.latente.probe

/**
 * Árvore de resultados da sonda.
 *
 * Um só modelo alimenta os dois formatos de saída: JSON para processar (é dele que sairão os
 * perfis de corpo e de objectivas) e texto para ler. Os valores guardam-se com o seu tipo, para
 * que os números saiam do JSON como números e não como cadeias.
 */
class Node(val name: String) {

    val fields = LinkedHashMap<String, Any?>()
    val children = ArrayList<Node>()

    fun put(key: String, value: Any?): Node {
        fields[key] = value
        return this
    }

    fun child(name: String): Node {
        val c = Node(name)
        children.add(c)
        return c
    }
}

/** Matriz 3x3 em ordem de linhas. Existe para os dois escritores a poderem formatar bem. */
class Mat3(val v: DoubleArray) {
    init {
        require(v.size == 9) { "Mat3 precisa de 9 valores" }
    }
}

// ---------------------------------------------------------------------------
// JSON
// ---------------------------------------------------------------------------

object Json {

    fun write(root: Node): String {
        val sb = StringBuilder(64 * 1024)
        node(sb, root, 0)
        sb.append('\n')
        return sb.toString()
    }

    private fun node(sb: StringBuilder, n: Node, depth: Int) {
        val pad = "  ".repeat(depth)
        val padIn = "  ".repeat(depth + 1)
        sb.append("{\n")
        sb.append(padIn).append("\"nome\": ").append(str(n.name))
        for ((k, v) in n.fields) {
            sb.append(",\n").append(padIn).append(str(k)).append(": ")
            value(sb, v, depth + 1)
        }
        if (n.children.isNotEmpty()) {
            sb.append(",\n").append(padIn).append("\"filhos\": [\n")
            for ((i, c) in n.children.withIndex()) {
                sb.append("  ".repeat(depth + 2))
                node(sb, c, depth + 2)
                if (i < n.children.size - 1) sb.append(',')
                sb.append('\n')
            }
            sb.append(padIn).append("]")
        }
        sb.append('\n').append(pad).append('}')
    }

    private fun value(sb: StringBuilder, v: Any?, depth: Int) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v.toString())
            is Int, is Long -> sb.append(v.toString())
            is Float, is Double -> sb.append(num(v as Number))
            is Mat3 -> {
                sb.append('[')
                for (i in 0 until 9) {
                    if (i > 0) sb.append(", ")
                    sb.append(num(v.v[i]))
                }
                sb.append(']')
            }
            is Collection<*> -> {
                if (v.isEmpty()) {
                    sb.append("[]")
                } else {
                    sb.append('[')
                    for ((i, e) in v.withIndex()) {
                        if (i > 0) sb.append(", ")
                        value(sb, e, depth)
                    }
                    sb.append(']')
                }
            }
            else -> sb.append(str(v.toString()))
        }
    }

    private fun num(n: Number): String {
        val d = n.toDouble()
        if (d.isNaN() || d.isInfinite()) return "null"
        if (d == Math.floor(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        return String.format(java.util.Locale.US, "%.6g", d)
    }

    private fun str(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

// ---------------------------------------------------------------------------
// Texto
// ---------------------------------------------------------------------------

object Txt {

    fun write(root: Node): String {
        val sb = StringBuilder(64 * 1024)
        node(sb, root, 0)
        return sb.toString()
    }

    private fun node(sb: StringBuilder, n: Node, depth: Int) {
        val pad = "  ".repeat(depth)
        if (depth == 0) {
            sb.append(n.name).append('\n')
            sb.append("=".repeat(n.name.length)).append("\n\n")
        } else {
            sb.append('\n').append(pad).append("── ").append(n.name).append('\n')
        }
        var width = 0
        for (k in n.fields.keys) if (k.length > width) width = k.length
        for ((k, v) in n.fields) {
            sb.append(pad).append("  ").append(k.padEnd(width)).append(" : ")
            append(sb, v, pad, width)
            sb.append('\n')
        }
        for (c in n.children) node(sb, c, depth + 1)
    }

    private fun append(sb: StringBuilder, v: Any?, pad: String, width: Int) {
        when (v) {
            null -> sb.append("—")
            is Mat3 -> {
                val cont = "\n" + pad + "  " + " ".repeat(width) + "   "
                for (r in 0 until 3) {
                    if (r > 0) sb.append(cont)
                    sb.append('[')
                    for (c in 0 until 3) {
                        if (c > 0) sb.append(", ")
                        sb.append(String.format(java.util.Locale.US, "%9.5f", v.v[r * 3 + c]))
                    }
                    sb.append(']')
                }
            }
            is Collection<*> -> {
                if (v.isEmpty()) sb.append("—") else sb.append(v.joinToString(", "))
            }
            else -> sb.append(v.toString())
        }
    }
}

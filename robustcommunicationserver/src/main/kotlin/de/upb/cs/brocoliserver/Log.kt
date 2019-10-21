package de.upb.cs.brocoliserver

fun main(args: Array<String>) {
    println("Hello, World")
}

class Log {
    companion object {
        fun e(tag: String, content: String, e: Exception? = null) {
            System.err.println("$tag: $content ${if (e != null) " - $e" else ""}")
        }

        fun d(tag: String, content: String, e: Exception? = null) {
            println("$tag: $content ${if (e != null) " - $e" else ""}")
        }

        fun i(tag: String, content: String) {
            println("$tag: $content")
        }
    }
}
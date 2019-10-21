package de.upb.cs.brocoliserver.standalone

import java.net.Socket

fun main(args: Array<String>) {
    val client =  Socket("localhost", 9099)
    val content = "hallo".toByteArray()
    client.getOutputStream().write(content.size)
    client.getOutputStream().write(content)

    client.getOutputStream().write(content.size)
    client.getOutputStream().write(content)
}
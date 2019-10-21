package de.upb.cs.brocoliserver.library

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer

import java.lang.reflect.Type

class InterfaceAdapter<T: Any> : JsonSerializer<T>, JsonDeserializer<T> {

    override fun serialize(thing: T, interfaceType: Type, context: JsonSerializationContext): JsonElement {

        val member = JsonObject()

        member.addProperty("type", thing::class.java.name)

        member.add("data", context.serialize(thing))

        return member
    }

    @Throws(JsonParseException::class)
    override fun deserialize(elem: JsonElement, interfaceType: Type, context: JsonDeserializationContext): T {
        val member = elem as JsonObject
        val typeString = get(member, "type")
        val data = get(member, "data")
        val actualType = typeForName(typeString)

        return context.deserialize(data, actualType)
    }

    private fun typeForName(typeElem: JsonElement): Type {
        try {
            return Class.forName(typeElem.asString)
        } catch (e: ClassNotFoundException) {
            throw JsonParseException(e)
        }

    }

    private operator fun get(wrapper: JsonObject, memberName: String): JsonElement {

        return wrapper.get(memberName) ?: throw JsonParseException(
                "no '$memberName' member found in json file.")
    }

}

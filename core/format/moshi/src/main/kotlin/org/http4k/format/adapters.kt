package org.http4k.format

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types.getRawType
import dev.forkhandles.values.AbstractValue
import org.http4k.contract.jsonschema.ArrayItem
import org.http4k.contract.jsonschema.ArrayItems
import org.http4k.contract.jsonschema.EmptyArray
import org.http4k.contract.jsonschema.OneOfArray
import org.http4k.contract.jsonschema.SchemaNode
import org.http4k.core.Status
import org.http4k.events.Event
import org.http4k.websocket.WsStatus
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.reflect.KClass

/**
 * Convenience class to create Moshi Adapter Factory
 */
open class SimpleMoshiAdapterFactory(vararg typesToAdapters: Pair<String, (Moshi) -> JsonAdapter<*>>) :
    JsonAdapter.Factory {
    private val mappings = typesToAdapters.toMap()

    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi) =
        mappings[getRawType(type).typeName]?.let { it(moshi) }
}

/**
 * Convenience function to create Moshi Adapter.
 */
inline fun <reified T : JsonAdapter<K>, reified K> adapter(noinline fn: (Moshi) -> T) = K::class.java.name to fn

/**
 * Convenience function to create Moshi Adapter Factory for a simple Moshi Adapter
 */
inline fun <reified K> JsonAdapter<K>.asFactory() = SimpleMoshiAdapterFactory(K::class.java.name to { this })

/**
 * Convenience function to add a custom adapter.
 */
inline fun <reified T : JsonAdapter<K>, reified K> Moshi.Builder.addTyped(fn: T): Moshi.Builder =
    add(K::class.java, fn)

/**
 * This adapter factory will capture ALL instances of a particular superclass/interface.
 */
abstract class IsAnInstanceOfAdapter<T : Any>(
    private val clazz: KClass<T>,
    private val resolveAdapter: Moshi.(KClass<T>) -> JsonAdapter<T> = { adapter(it.java) }
) : JsonAdapter.Factory {
    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi) =
        with(getRawType(type)) {
            when {
                isA(clazz.java) -> moshi.resolveAdapter(clazz)
                else -> null
            }
        }

    private fun Class<*>?.isA(testCase: Class<*>): Boolean =
        this?.let { testCase != this && testCase.isAssignableFrom(this) } ?: false
}

/**
 * These adapters are the edge case adapters for dealing with Moshi
 */
object ThrowableAdapter : IsAnInstanceOfAdapter<Throwable>(Throwable::class)

object MapAdapter : IsAnInstanceOfAdapter<Map<*, *>>(Map::class)

object ListAdapter : IsAnInstanceOfAdapter<List<*>>(List::class)

object SetAdapter : IsAnInstanceOfAdapter<Set<*>>(Set::class)

object EventAdapter : JsonAdapter.Factory {
    override fun create(p0: Type, p1: MutableSet<out Annotation>, p2: Moshi) =
        if (p0.typeName == Event::class.java.typeName) p2.adapter(Any::class.java) else null
}

object ProtocolStatusAdapter : JsonAdapter.Factory {
    override fun create(p0: Type, p1: MutableSet<out Annotation>, p2: Moshi) =
        when (p0.typeName) {
            WsStatus::class.java.typeName -> p2.adapter(WsStatus::class.java)
            Status::class.java.typeName -> p2.adapter(Status::class.java)
            else -> null
        }
}

object ProhibitUnknownValuesAdapter : JsonAdapter.Factory {
    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi) =
        when {
            (type as? Class<*>)?.superclass == AbstractValue::class.java -> throw UnmappedValue(type)
            else -> null
        }
}

object MoshiNodeAdapter : JsonAdapter.Factory {
    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi) =
        with(getRawType(type)) {
            when {
                isA(MoshiNode::class.java) -> object : JsonAdapter<MoshiNode>() {
                    override fun fromJson(p0: JsonReader) = MoshiNode.wrap(p0.readJsonValue())

                    override fun toJson(p0: JsonWriter, p1: MoshiNode?) {
                        p1?.let { moshi.adapter(Any::class.java).toJson(p0, it.unwrap()) }
                    }
                }

                else -> null
            }
        }

    private fun Class<*>?.isA(testCase: Class<*>): Boolean =
        this?.let { testCase.isAssignableFrom(this) } ?: false
}

class UnmappedValue(type: Type) : Exception("unmapped type $type")

object SchemaNodeJsonAdapterFactory : JsonAdapter.Factory {
    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
        val rawType = getRawType(type)

        return when {
            SchemaNode::class.java == rawType -> SchemaNodeJsonAdapter(moshi)
            rawType.isAssignableFrom(Iterable::class.java) -> {
                (type as? ParameterizedType)?.actualTypeArguments
                    ?.firstOrNull()
                    ?.takeIf { getRawType(it) == SchemaNode::class.java }
                    ?.let { SchemaNodeListJsonAdapter(moshi) }
            }

            else -> null
        }

    }
}

private class SchemaNodeJsonAdapter(private val moshi: Moshi) : JsonAdapter<SchemaNode>() {
    override fun toJson(writer: JsonWriter, value: SchemaNode?) {
        when (value) {
            null -> writer.nullValue()
            else -> {
                writer.beginObject()
                value.entries
                    .forEach { (key, mapValue) ->
                        if (mapValue != null) {
                            writer.name(key)
                            moshi.adapter<Any>(mapValue::class.java).toJson(writer, mapValue)
                        }
                    }
                writer.endObject()
            }
        }
    }

    override fun fromJson(reader: JsonReader): SchemaNode {
        throw UnsupportedOperationException("SchemaNode deserialization is not supported")
    }
}

private class SchemaNodeListJsonAdapter(moshi: Moshi) : JsonAdapter<List<SchemaNode>>() {
    private val nodeAdapter = SchemaNodeJsonAdapter(moshi)

    override fun toJson(writer: JsonWriter, value: List<SchemaNode>?) {
        when (value) {
            null -> writer.nullValue()
            else -> {
                writer.beginArray()
                value.forEach { nodeAdapter.toJson(writer, it) }
                writer.endArray()
            }
        }

    }

    override fun fromJson(reader: JsonReader): List<SchemaNode> {
        throw UnsupportedOperationException("SchemaNode list deserialization is not supported")
    }
}

object ArrayItemsJsonAdapterFactory : JsonAdapter.Factory {
    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
        val rawType = getRawType(type)

        return when {
            ArrayItems::class.java.isAssignableFrom(rawType) -> ArrayItemsJsonAdapter(moshi)
            else -> null
        }
    }
}


private class ArrayItemsJsonAdapter(private val moshi: Moshi) : JsonAdapter<ArrayItems>() {
    override fun toJson(writer: JsonWriter, value: ArrayItems?) {
        when (value) {
            null -> writer.nullValue()
            is EmptyArray -> writer.beginObject().endObject()
            is OneOfArray -> {
                writer.beginObject()
                writer.name("oneOf")
                moshi.adapter<Any>(value.oneOf::class.java).toJson(writer, value.oneOf)
                writer.endObject()
            }
            is ArrayItem.Ref -> {
                writer.beginObject()
                writer.name("\$ref")
                moshi.adapter<Any>(String::class.java).toJson(writer, value.`$ref`)
                writer.endObject()
            }
            is ArrayItem.NonObject -> {
                writer.beginObject()
                writer.name("type")
                moshi.adapter<Any>(String::class.java).toJson(writer, value.type)
                if (value.format != null) {
                    writer.name("format")
                    moshi.adapter<Any>(value.format!!::class.java).toJson(writer, value.format)
                }
                writer.endObject()
            }
            is ArrayItem.Array -> {
                writer.beginObject()
                writer.name("type")
                moshi.adapter<Any>(String::class.java).toJson(writer, value.type)
                writer.name("items")
                moshi.adapter(ArrayItems::class.java).toJson(writer, value.items)
                if (value.format != null) {
                    writer.name("format")
                    moshi.adapter<Any>(value.format!!::class.java).toJson(writer, value.format)
                }
                writer.endObject()
            }
        }
    }

    override fun fromJson(reader: JsonReader): ArrayItems {
        throw UnsupportedOperationException("ArrayItems deserialization is not supported")
    }
}

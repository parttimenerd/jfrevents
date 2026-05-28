package me.bechberger.collector

import jdk.jfr.consumer.RecordedObject
import me.bechberger.collector.xml.AbstractType
import me.bechberger.collector.xml.Event
import me.bechberger.collector.xml.EventExample
import me.bechberger.collector.xml.Example
import me.bechberger.collector.xml.FieldType
import me.bechberger.collector.xml.Type
import me.bechberger.collector.xml.readXmlAs
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.writeText

/** Adds examples to the events and types */
class ExampleAdder(val metadata: me.bechberger.collector.xml.Metadata) {

    fun addEventsFromFile(label: String, description: String, file: Path, platform: String? = null) {
        assert(metadata.exampleFiles.none { it.label == label || it.description == description })
        val processor = Processor(file)
        processor.process()
        val id = metadata.exampleFiles.size
        metadata.exampleFiles.add(me.bechberger.collector.xml.ExampleFile(label, description, platform))
        addEventExamples(id, processor)
    }

    private fun addEventExamples(id: Int, processor: Processor) {
        val events = metadata.events.map { "jdk." + it.name to it }.toMap()
        for ((type, e) in processor.events) {
            val event = e.random()
            if (type.name in events) {
                val metadataEvent = events[type.name]!!
                addToEvent(id, metadataEvent, event)
                metadataEvent.appearedIn.add(id)
            }
        }
    }

    private fun addToEvent(id: Int, event: Event, obj: RecordedObject): EventExample {
        return addToType(id, event, obj, EventExample(id))
    }

    private fun addToAbstractType(id: Int, type: AbstractType<Example>?, value: Any?): Example {
        type?.let {
            type.appearedIn.add(id)
        }
        return when (value) {
            is RecordedObject -> {
                when (type) {
                    null -> addToType(id, type, value)
                    is Type -> addToType(id, type, value)
                    else -> addToType(id, null, value).also {
                        if (type.examples.size < MAX_EXAMPLES) {
                            type.examples.add(it)
                        }
                    }
                }
            }
            null -> when (type) {
                null -> Example(id, FieldType.NULL)
                else -> Example(id, FieldType.NULL).also {
                    if (type.examples.size < MAX_EXAMPLES) {
                        type.examples.add(it)
                    }
                }
            }
            else -> {
                Example(id, FieldType.STRING).also {
                    var string = value.toString()
                    if ("/jfreventscollector/" in string) {
                        string = "[...]/jfreventscollector/" + string.substringAfter("/jfreventscollector/")
                    }
                    if ("/Users/" in string) {
                        string = string.replace(Regex("[/](Users|home)[/][a-zA-Z0-9]+"), "[...]")
                    }
                    it.stringValue = if (string.length < MAX_TEXT_LENGTH) {
                        string
                    } else {
                        it.isTruncated = true
                        string.substring(0, MAX_TEXT_LENGTH)
                    }
                    type?.examples?.let { examples ->
                        if (examples.size < MAX_EXAMPLES) {
                            examples.add(it)
                        }
                    }
                }
            }
        }.also { it.typeName = type?.name }
    }

    private fun addToType(id: Int, type: Type<Example>?, obj: RecordedObject): Example {
        return addToType(id, type, obj, Example(id, FieldType.OBJECT))
    }

    data class FieldAndType(
        val field: String,
        val type: AbstractType<Example>?,
        val typeName: String?,
        val contentType: String?
    )

    private fun <T : Example> addToType(
        id: Int,
        type: Type<T>?,
        obj: RecordedObject?,
        example: T
    ): T {
        if (obj == null) {
            example.type = FieldType.NULL
            return example
        }
        val map = mutableMapOf<String, Example>()

        val fieldNames = (
            type?.fields?.map {
                FieldAndType(
                    it.name,
                    metadata.getType(it.contentType, it.type),
                    it.type,
                    it.contentType
                )
            }
                ?: obj.fields.map {
                    FieldAndType(
                        it.name,
                        metadata.getType(it.contentType, it.typeName),
                        it.typeName,
                        it.contentType
                    )
                }
            ).toMutableList()
        if (type != null && type is Event) {
            if (type.stackTrace) {
                fieldNames.add(FieldAndType("stackTrace", metadata.getType("StackTrace"), "StackTrace", null))
            }
            if (type.thread) {
                fieldNames.add(FieldAndType("thread", metadata.getType("Thread"), "Thread", null))
            }
            if (type.startTime) {
                fieldNames.add(FieldAndType("startTime", metadata.getType("millis"), "long", "millis"))
            }
        }
        for (entry in fieldNames) {
            if (!obj.hasField(entry.field)) {
                continue
            }
            map[entry.field] = when (val fieldValue = obj.getValue<Any>(entry.field)) {
                null -> {
                    addToAbstractType(id, entry.type, null)
                }
                is Array<*> -> {
                    val array = obj.getValue<Array<*>>(entry.field)
                    Example(id, FieldType.ARRAY).also {
                        it.typeName = entry.type?.name
                        if (array.size > MAX_ARRAY_LENGTH) {
                            it.isTruncated = true
                        }
                        it.arrayValue = array.take(if (entry.field != "frames") MAX_ARRAY_LENGTH else 1)
                            .map { elem -> addToAbstractType(id, entry.type, elem) }.toMutableList()
                    }
                }
                else -> {
                    addToAbstractType(id, entry.type, fieldValue)
                }
            }.also {
                it.typeName = entry.typeName?.let {
                    (
                        metadata.getSpecificType(entry.typeName, metadata.types)
                            ?: metadata.getSpecificType(entry.typeName, metadata.xmlTypes)
                        )?.name ?: entry.typeName
                }
                it.contentTypeName =
                    entry.contentType?.let {
                        metadata.getSpecificType(
                            entry.contentType,
                            metadata.xmlContentTypes
                        )?.name
                    } ?: entry.contentType
            }
        }
        return example.also {
            it.typeName = type?.name
            it.objectValue = map; it.type = FieldType.OBJECT; type?.examples?.let { examples ->
                if (examples.size < MAX_EXAMPLES) {
                    examples.add(it)
                }
            }
        }
    }

    companion object {
        const val MAX_EXAMPLES = 3
        const val MAX_ARRAY_LENGTH = 2
        const val MAX_TEXT_LENGTH = 300
    }
}

fun main(args: Array<String>) {
    // Optional second argument: --platform=<value> applies to all examples in this call
    val platform: String?
    val startIdx: Int
    if (args.size > 1 && args[1].startsWith("--platform=")) {
        platform = args[1].removePrefix("--platform=")
        startIdx = 2
    } else {
        platform = null
        startIdx = 1
    }
    val tripletArgs = args.size - startIdx - 1  // subtract metadata path and output path
    if (args.size < 4 || tripletArgs % 3 != 0) {
        println(
            "Usage: ExampleAdder <path to metadata.xml> [--platform=<value>] <label of file> <description of file> <JFR file> ... " + "<path to resulting metadata.xml>"
        )
        return
    }
    val metadataPath = Paths.get(args[0])
    val metadata = metadataPath.readXmlAs(me.bechberger.collector.xml.Metadata::class.java)
    println("Read metadata from $metadataPath: ${metadata.events.size} events, ${metadata.types.size} types")
    val eventAdder = ExampleAdder(metadata)
    for (i in startIdx until args.size step 3) {
        if (args.size - i < 3) {
            break
        }
        val label = args[i]
        val description = args[i + 1]
        val file = Paths.get(args[i + 2])
        eventAdder.addEventsFromFile(label, description, file, platform)
    }
    val out = args[args.size - 1]
    if (out == "-") {
        println(metadata)
    } else {
        Paths.get(out).writeText(metadata.toString())
    }
}

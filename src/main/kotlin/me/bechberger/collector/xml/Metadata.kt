package me.bechberger.collector.xml

/**
 * Contains the XML mapper classes for the metadata file
 */

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText
import java.nio.file.Path
import java.util.Objects

@JacksonXmlRootElement
class Metadata {

    /**
     * Events where the JDK sets both start_time and end_time to the same value (usually start time),
     * obtained by manually inspecting the JDK 22 source code
     */
    val fakeEndTimes = setOf(
        "ExecutionSample",
        "NativeMethodSample",
        "GCLocker",
        "ObjectCount",
        "ObjectCounterAfterGC",
        "FinalizerStatistics",
        "ModuleRequire",
        "ModuleExport",
        "NetworkUtilization",
        "InitialEnvironmentVariable",
        "NativeLibrary",
        "InitialSystemProperty",
        "ThreadAllocationStatistics",
        "GCHeapMemoryUsage",
        "GCHeapMemoryPoolUsage",
    )

    @JacksonXmlProperty(localName = "Event")
    @JacksonXmlElementWrapper(useWrapping = false) // see https://github.com/FasterXML/jackson-dataformat-xml/issues/275#issuecomment-352700025
    var events: MutableList<Event> = mutableListOf()
        set(value) {
            field.addAll(value)
            value.filter { it.name in fakeEndTimes && it.duration }
                .forEach { it.markEndTimeAsFake() }
        }

    @JacksonXmlProperty(localName = "Type")
    @JacksonXmlElementWrapper(useWrapping = false)
    var types: MutableList<Type<Example>> = mutableListOf()
        set(value) {
            field.addAll(value)
        }

    @JacksonXmlProperty(localName = "Relation")
    @JacksonXmlElementWrapper(useWrapping = false)
    var relations: MutableList<Relation> = mutableListOf()
        set(value) {
            field.addAll(value)
        }

    @JacksonXmlProperty(localName = "XmlType")
    @JacksonXmlElementWrapper(useWrapping = false)
    var xmlTypes: MutableList<XmlType> = mutableListOf()
        set(value) {
            field.addAll(value)
        }

    @JacksonXmlProperty(localName = "XmlContentType")
    @JacksonXmlElementWrapper(useWrapping = false)
    var xmlContentTypes: MutableList<XmlContentType> = mutableListOf()
        set(value) {
            field.addAll(value)
        }

    @JacksonXmlProperty(localName = "Configuration")
    @JacksonXmlElementWrapper(useWrapping = false)
    var configurations: MutableList<Configuration> = ArrayList()
        set(value) {
            field.addAll(value)
        }

    @JacksonXmlProperty(localName = "ExampleFile")
    @JacksonXmlElementWrapper(useWrapping = false)
    var exampleFiles: MutableList<ExampleFile> = ArrayList()
        set(value) {
            field.addAll(value)
        }

    class GraalVMInfo(
        @JacksonXmlProperty(isAttribute = true) val version: String,
        @JacksonXmlProperty(isAttribute = true) val url: String,
        @JacksonXmlProperty(isAttribute = true) val tag: String,
        @JacksonXmlProperty(isAttribute = true) val foundEvents: Boolean = true,
    )

    /** GitHub URL of the main folder*/
    @JacksonXmlProperty(localName = "url", isAttribute = true)
    var url: String? = null

    /** GitHub URL of the main folder with specific commit */
    @JacksonXmlProperty(localName = "permaUrl", isAttribute = true)
    var permanentUrl: String? = null

    @JacksonXmlProperty(localName = "GraalVMInfo")
    @JacksonXmlElementWrapper(useWrapping = false)
    var graalVMInfo: GraalVMInfo? = null

    fun copy(): Metadata {
        val meta = Metadata()
        meta.events = events.toMutableList()
        meta.types = types
        meta.relations = relations
        meta.xmlTypes = xmlTypes
        meta.xmlContentTypes = xmlContentTypes.toMutableList()
        meta.configurations = configurations.toMutableList()
        return meta
    }

    fun findMatchingContentAnnotationOrAdd(
        annotationType: String,
        valueExpression: String,
        hasFrequency: Boolean = false,
    ): XmlContentType {
        return xmlContentTypes.find { it.matchesAnnotation(annotationType, valueExpression) } ?: XmlContentType.create(
            annotationType,
            valueExpression,
            hasFrequency,
        ).also { xmlContentTypes.add(it) }
    }

    fun findOrCreateContentType(name: String, annotation: String, label: String): XmlContentType {
        return xmlContentTypes.find { it.name == name } ?: XmlContentType.create(name, annotation, label)
            .also { xmlContentTypes.add(it) }
    }

    fun findMatchingType(fullyQualifiedJavaType: String): XmlType? {
        return xmlTypes.find { it.matchesJavaType(fullyQualifiedJavaType) }
    }

    override fun toString() = objectToXml(this)

    fun read(path: Path) = path.readXmlAs(Metadata::class.java)

    fun String.normalizeName() = this.split(".").last().lowercase()

    @get:JsonIgnore
    private val typesCache: MutableMap<String, AbstractType<Example>> by lazy {
        (types + xmlTypes + xmlContentTypes).associateBy { it.name.normalizeName() }.toMutableMap()
    }

    @get:JsonIgnore
    private val eventsCache: MutableMap<String, Event> by lazy {
        events.associateBy { it.name }.toMutableMap()
    }

    fun getType(name: String): AbstractType<Example>? {
        val normalized = name.normalizeName()
        return typesCache.get(normalized) ?: run {
            val found = listOf(
                types,
                xmlTypes,
                xmlContentTypes,
            ).firstNotNullOfOrNull { it.find { it.name.normalizeName() == normalized } }
            if (found != null) {
                typesCache[normalized] = found
            }
            found
        }
    }

    @get:JsonIgnore
    private val typeCaches: MutableMap<MutableList<AbstractType<*>>, MutableMap<String, AbstractType<*>>> =
        mutableMapOf()

    @Suppress("UNCHECKED_CAST")
    private fun <T : AbstractType<*>> getTypeCache(ts: MutableList<T>): MutableMap<String, T> {
        return typeCaches.getOrPut(ts as MutableList<AbstractType<*>>) {
            ts.associateBy { it.name.normalizeName() }.toMutableMap()
        } as MutableMap<String, T>
    }

    fun clearTypesCache() {
        typeCaches.clear()
    }

    /** clear types cache if you change the source list after calling this method previously */
    fun <T : AbstractType<*>> getSpecificType(name: String, source: MutableList<T>): T? {
        return getTypeCache(source)[name.normalizeName()]
    }

    fun getEvent(name: String): Event? {
        return eventsCache.get(name) ?: run {
            val found = events.firstOrNull { it.name == name || "jdk.${it.name}" == name }
            if (found != null) {
                eventsCache[name] = found
            }
            found
        }
    }

    fun getType(vararg names: String?): AbstractType<Example>? =
        names.filterNotNull().firstNotNullOfOrNull { getType(it) }

    fun getConfiguration(label: String, provider: String? = null): Configuration? {
        return configurations.firstOrNull { it.label == label && (provider == null || it.provider == provider) }
    }

    private fun combineTypes(perVersion: List<Pair<Int, Metadata>>): List<Pair<AbstractType<*>, List<Pair<Int, AbstractType<*>?>>>> {
        val accessors = arrayOf({ m: Metadata -> m.types }, { it.xmlTypes }, { it.xmlContentTypes })
        return accessors.flatMap { acc ->
            val types = mutableMapOf<String, MutableMap<Int, AbstractType<*>>>()
            val ownTypes = mutableMapOf<String, AbstractType<*>>()
            perVersion.forEach { (version, meta) ->
                acc(meta).forEach { type ->
                    types.getOrPut(type.name) { mutableMapOf<Int, AbstractType<*>>() }[version] = type
                }
            }
            acc(this).forEach { type ->
                ownTypes[type.name] = type
            }
            ownTypes.map { (name, type) ->
                type to perVersion.map { (version, _) ->
                    version to types[name]?.get(version)
                }
            }
        }
    }

    fun setSupportedJDKs(perVersion: List<Pair<Int, Metadata>>) {
        combineTypes(perVersion).forEach { (t, ts) ->
            (t as AbstractType<Example>).setSupportedJDKs(ts as List<Pair<Int, AbstractType<Example>?>>)
        }
        events.forEach { e -> e.setSupportedJDKs(perVersion.map { (v, m) -> v to m.getEvent(e.name) }) }

        configurations.forEach { c -> c.setSupportedJDKs(perVersion.map { (v, m) -> v to m.getConfiguration(c.label) }) }
    }

    @SuppressWarnings("UNCHECKED_CAST")
    fun addAdditionalDescription(other: Metadata) {
        combineTypes(listOf(0 to other)).forEach { (t, ts) ->
            ts.first().second?.let {
                (t as AbstractType<Example>).addAdditionalDescription(it as AbstractType<Example>)
            }
        }
        other.events.forEach { e ->
            getEvent(e.name)?.addAdditionalDescription(e)
        }
    }
}

class Event() : Type<EventExample>() {

    companion object {
        val GRAAL_ONLY = "GRAAL_ONLY"
        val JDK_ONLY = "JDK_ONLY"
        val JDK_AND_GRAAL = "JDK_AND_GRAAL"
    }

    /** Category, subcategory, subsubcategory */
    @JacksonXmlProperty(isAttribute = true)
    lateinit var category: String

    @JacksonXmlProperty(isAttribute = true)
    var description: String? = null

    /**
     * Every event has a start time, the JFR
     * startTime setting enables the duration property.
     */
    @JsonIgnore
    var startTime: Boolean = true

    /** has an duration field */
    @JacksonXmlProperty(isAttribute = true)
    var durationField: Boolean = true

    /** has an endTime field */
    @JacksonXmlProperty(isAttribute = true)
    var endTime: Boolean = true

    /** has a duration field */
    @JacksonXmlProperty(isAttribute = true, localName = "startTime")
    var duration: Boolean = true

    /** has a fake endTime field */
    @JacksonXmlProperty(isAttribute = true)
    var fakeEndTime: Boolean = false

    @JacksonXmlProperty(isAttribute = true)
    var fakeDuration: Boolean = false

    fun markEndTimeAsFake() {
        fakeEndTime = true
        fakeDuration = true
    }

    fun setEndTimeField(duration: Boolean) {
        this.duration = duration
        this.durationField = duration
        this.endTime = duration
    }

    @JacksonXmlProperty(isAttribute = true)
    var experimental: Boolean = false

    /** has an eventThread property? */
    @JacksonXmlProperty(isAttribute = true)
    var thread: Boolean = false

    @JacksonXmlProperty(isAttribute = true)
    var stackTrace: Boolean = false

    /** Internal can be ignored */
    @JacksonXmlProperty(isAttribute = true)
    var internal: Boolean = false

    @JacksonXmlProperty(isAttribute = true)
    var throttle: Boolean = false

    @JacksonXmlProperty(isAttribute = true)
    var cutoff: Boolean = false

    /** just for events from code, all other events are enabled by default */
    @JacksonXmlProperty(isAttribute = true)
    var enabled: Boolean = true

    @JacksonXmlProperty(isAttribute = true)
    var period: String? = null

    /**
     * Omitted methods/classes in the stacktrace, see {@link jdk.jfr.events.StackFilter},
     * only avaiable in JDK 22+
     */
    @JacksonXmlProperty(localName = "StackFilter")
    var stackFilter: List<String> = ArrayList()

    @JacksonXmlProperty(localName = "Configuration")
    @JacksonXmlElementWrapper(useWrapping = false)
    var configurations: MutableList<SingleEventConfiguration> = ArrayList()
        set(value) {
            field.addAll(value)
        }

    @JacksonXmlProperty(isAttribute = true)
    var source: String? = null

    @JacksonXmlProperty(isAttribute = true)
    var graalSource: String? = null

    @JacksonXmlProperty(localName = "AIGeneratedDescription")
    var aiGeneratedDescription: AIGeneratedDescription? = null



    @JacksonXmlProperty(isAttribute = true)
    var includedInVariant: String = JDK_ONLY

    fun categories(): List<String> = category.split(", ")

    /**
     * Possible source code matches
     * <p>
     * Multiple entries might have the same path, but different lines
     */
    @JacksonXmlProperty(localName = "SourceContext")
    @JacksonXmlElementWrapper(useWrapping = false)
    var context: List<EventSourceContext>? = null

    constructor(
        name: String = "",
        category: String = "",
        label: String = "",
        description: String? = null,
        endTime: Boolean = false,
        fakeEndTime: Boolean = false,
        experimental: Boolean = false,
        thread: Boolean = false,
        stackTrace: Boolean = false,
        internal: Boolean = false,
        throttle: Boolean = false,
        cutoff: Boolean = false,
        enabled: Boolean = true,
        period: String? = null,
        stackFilter: List<String> = ArrayList(),
        fields: MutableList<Field> = ArrayList(),
        configurations: MutableList<SingleEventConfiguration> = ArrayList(),
        source: String? = null,
        graalSource: String? = null,
        examples: MutableSet<EventExample> = mutableSetOf(),
        additionalDescription: String? = null,
        appearedIn: MutableSet<Int> = mutableSetOf(),
        aiGeneratedDescription: AIGeneratedDescription? = null,
        includedInVariant: String = JDK_ONLY
    ) : this() {
        this.name = name
        this.category = category
        this.label = label
        this.description = description
        setEndTimeField(endTime)
        this.fakeEndTime = fakeEndTime
        if (fakeEndTime) {
            this.fakeDuration = true
        }
        this.experimental = experimental
        this.thread = thread
        this.stackTrace = stackTrace
        this.internal = internal
        this.throttle = throttle
        this.cutoff = cutoff
        this.enabled = enabled
        this.period = period
        this.stackFilter = stackFilter
        this.fields = fields
        this.configurations = configurations
        this.source = source
        this.graalSource = graalSource
        this.examples = examples
        this.additionalDescription = additionalDescription
        this.appearedIn = appearedIn
        this.aiGeneratedDescription = aiGeneratedDescription
        this.includedInVariant = includedInVariant
    }

    fun merge(other: Event): Event {
        fun merge(first: String, second: String): String {
            return second.ifEmpty { first }
        }

        fun merge(first: String?, second: String?): String? {
            return if (second?.isNotEmpty() == true) second else first
        }

        return Event(
            merge(name, other.name),
            merge(category, other.category),
            merge(label, other.label),
            merge(description, other.description),
            endTime || other.endTime,
            fakeEndTime || other.fakeEndTime,
            experimental || other.experimental,
            thread || other.thread,
            stackTrace || other.stackTrace,
            internal || other.internal,
            throttle || other.throttle,
            cutoff || other.cutoff,
            other.enabled,
            period ?: other.period,
            (stackFilter + other.stackFilter).toMutableList(),
            (fields + other.fields).toMutableList(),
            (configurations + other.configurations).toMutableList(),
            merge(source, other.source),
            merge(graalSource, other.graalSource),
            (examples + other.examples).toMutableSet(),
            (additionalDescription ?: "") + (other.additionalDescription ?: ""),
            (appearedIn + other.appearedIn).toMutableSet(),
            aiGeneratedDescription ?: other.aiGeneratedDescription,
            mergeVariant(includedInVariant, other.includedInVariant)
        )
    }

    private fun mergeVariant(first: String, second: String): String {
        return when {
            first == GRAAL_ONLY && second == GRAAL_ONLY -> GRAAL_ONLY
            first == JDK_ONLY && second == JDK_ONLY -> JDK_ONLY
            else -> JDK_AND_GRAAL
        }
    }

    override fun toString() = objectToXml(this)

    override fun setSupportedJDKs(perVersion: List<Pair<Int, AbstractType<EventExample>?>>) {
        super.setSupportedJDKs(perVersion)
        configurations.forEach { conf ->
            conf.setSupportedJDKs(
                perVersion.map { (v, t) ->
                    v to t?.let {
                        (t as Event).configurations.find { it.id == conf.id }
                    }
                },
            )
        }
        fields.forEach { field ->
            field.setSupportedJDKs(
                perVersion.map { (v, t) ->
                    v to t?.let {
                        (t as Event).getField(
                            field.name,
                        )
                    }
                },
                perVersion
            )
        }
    }

    @JsonIgnore
    fun isGraalOnly() = includedInVariant == GRAAL_ONLY
    @JsonIgnore
    fun isJDKOnly() = includedInVariant == JDK_ONLY
    @JsonIgnore
    fun isInJDKAndGraal() = includedInVariant == JDK_AND_GRAAL

    fun includedInGraal() {
        includedInVariant = when (includedInVariant) {
            JDK_ONLY -> JDK_AND_GRAAL
            else -> GRAAL_ONLY
        }
    }

    fun contextPaths() = context?.let {  it.map { it.path }.distinct().sorted() } ?: emptyList()

    fun sortedContext() = context?.sortedBy { it.path } ?: emptyList()

    fun hasContext() = context.isNullOrEmpty().not()
}

/** Description generated by AI based on the OpenJDK source files */
class AIGeneratedDescription {
    /** Description of the event in markdown format */
    var description: String

    /** paths of used sources that are related to event creation and usage */
    @JacksonXmlProperty(isAttribute = false)
    var sources: List<String>

    constructor(description: String, sources: List<String>) {
        this.description = description
        this.sources = sources
    }

    fun withDescription(description: String) = AIGeneratedDescription(description, sources)
}

class ExampleFile() {
    @JacksonXmlProperty(isAttribute = true)
    lateinit var label: String

    @JacksonXmlProperty(isAttribute = true)
    var platform: String? = null

    @JacksonXmlText
    lateinit var description: String

    constructor(label: String, description: String, platform: String? = null) : this() {
        this.label = label
        this.description = description
        this.platform = platform
    }
}

enum class FieldType {
    @JsonProperty("string")
    STRING,

    @JsonProperty("object")
    OBJECT,

    @JsonProperty("array")
    ARRAY,

    @JsonProperty("null")
    NULL,
}

open class Example() {
    /** id of the example file */
    @JacksonXmlProperty(isAttribute = true)
    var exampleFile = -1

    @JacksonXmlProperty(isAttribute = true)
    lateinit var type: FieldType

    @JacksonXmlProperty(isAttribute = false)
    var stringValue: String? = null

    @JacksonXmlProperty(isAttribute = false)
    var arrayValue: MutableList<Example>? = null

    @JacksonXmlProperty(isAttribute = false)
    var objectValue: MutableMap<String, Example>? = null

    @JacksonXmlProperty(isAttribute = true)
    var isTruncated: Boolean = false

    @JacksonXmlProperty(isAttribute = true)
    var typeName: String? = null

    @JacksonXmlProperty(isAttribute = true)
    var contentTypeName: String? = null

    constructor(exampleFile: Int, type: FieldType) : this() {
        this.exampleFile = exampleFile
        this.type = type
    }

    override fun hashCode(): Int {
        return Objects.hash(
            exampleFile,
            type,
            isTruncated,
            stringValue,
            arrayValue,
            objectValue,
            typeName,
            contentTypeName,
        )
    }

    override fun equals(other: Any?): Boolean {
        return when (other) {
            is Example -> {
                exampleFile == other.exampleFile && type == other.type && stringValue == other.stringValue && arrayValue == other.arrayValue && objectValue == other.objectValue && isTruncated == other.isTruncated && typeName == other.typeName && contentTypeName == other.contentTypeName
            }

            else -> false
        }
    }
}

class EventExample(exampleFile: Int) : Example(exampleFile, FieldType.OBJECT)

class EventSourceContext() {
    @JacksonXmlProperty(isAttribute = true)
    lateinit var path: String

    /** Start line of the context in the file */
    @JacksonXmlProperty(isAttribute = true)
    var startLine: Int = -1

    /** End line of the context in the file */
    @JacksonXmlProperty(isAttribute = true)
    var endLine: Int = -1

    /** Matched lines in the file, {@code startLine <= line in lines <= endLine} */
    @JacksonXmlProperty
    lateinit var lines: List<Int>

    /**
     * Code snippet of the source file, [startLine, endLine] is the range of the context
     */
    @JacksonXmlProperty
    lateinit var snippet: String

    fun matchedLinesInSnippet(): List<Int> {
        return lines.map { it - startLine }
    }
}

class SingleEventConfiguration : WithJDKs<SingleEventConfiguration> {
    /** index in the configurations list, obtain label, ... from there */
    @JacksonXmlProperty(isAttribute = true)
    var id: Int = -1

    @JacksonXmlProperty(localName = "Setting")
    var settings: List<EventSetting> = ArrayList()

    constructor(id: Int = -1, settings: List<EventSetting> = ArrayList()) {
        this.id = id
        this.settings = settings
    }

    override fun setSupportedJDKs(perVersion: List<Pair<Int, SingleEventConfiguration?>>) {
        super.setSupportedJDKs(perVersion)
        settings.forEach { setting -> setting.setSupportedJDKs(perVersion.map { (v, s) -> v to s?.let { s.settings.find { it.name == setting.name } } }) }
    }
}

open class AbstractType<E : Example> : WithJDKs<AbstractType<E>>() {
    @JacksonXmlProperty(isAttribute = true)
    lateinit var name: String

    @JacksonXmlProperty(isAttribute = true)
    var label: String = ""

    @JacksonXmlProperty(localName = "Example")
    var examples: MutableSet<E> = mutableSetOf()

    /** appeared in the following example files */
    @JacksonXmlProperty(localName = "appearedIn")
    var appearedIn: MutableSet<Int> = mutableSetOf()

    @JacksonXmlProperty(isAttribute = false)
    var additionalDescription: String? = null

    override fun toString() = objectToXml(this)

    open fun addAdditionalDescription(other: AbstractType<E>) {
        additionalDescription = other.additionalDescription
    }
}

open class Type<E : Example> : AbstractType<E>() {

    @JacksonXmlProperty(localName = "Field")
    var fields: MutableList<Field> = ArrayList()

    fun getField(name: String): Field? {
        return fields.find { it.name == name }
    }

    override fun setSupportedJDKs(perVersion: List<Pair<Int, AbstractType<E>?>>) {
        super.setSupportedJDKs(perVersion)
        fields.forEach { field ->
            val allFields = perVersion.map { (v, t) ->
                v to t?.let {
                    (t as Type).getField(
                        field.name,
                    )
                }
            }
            if (allFields.filter { it.second != null }.isNotEmpty()) {
                field.setSupportedJDKs(allFields)
            } else {
                // I don't know why the fields are empty of the type, but it happens
                field.jdks = perVersion.filter { it.second != null }.map { it.first }.sorted()
            }

        }
    }

    override fun addAdditionalDescription(other: AbstractType<E>) {
        assert(other is Type)
        additionalDescription = other.additionalDescription
        fields.forEach { field ->
            (other as Type).getField(field.name)?.let {
                field.addAdditionalDescription(it)
            }
        }
    }
}

enum class Transition {
    @JsonProperty("to")
    TO,

    @JsonProperty("from")
    FROM,
}

class Field : WithJDKs<Field>() {
    @JacksonXmlProperty(isAttribute = true)
    lateinit var type: String

    @JacksonXmlProperty(isAttribute = true)
    lateinit var name: String

    @JacksonXmlProperty(isAttribute = true)
    lateinit var label: String

    @JacksonXmlProperty(isAttribute = true)
    var relation: String? = null

    @JacksonXmlProperty(isAttribute = true)
    var contentType: String? = null

    @JacksonXmlProperty(isAttribute = true)
    var description: String? = null

    @JacksonXmlProperty(isAttribute = true)
    var struct: Boolean = false

    @JacksonXmlProperty(isAttribute = true)
    var experimental: Boolean = false

    @JacksonXmlProperty(isAttribute = true)
    var transition: Transition? = null

    @JacksonXmlProperty(isAttribute = true)
    var array: Boolean = false

    @JacksonXmlText
    var additionalDescription: String? = null

    override fun toString() = objectToXml(this)

    fun addAdditionalDescription(other: Field) {
        additionalDescription = other.additionalDescription
    }

    fun setSupportedJDKs(perVersion: List<Pair<Int, Field?>>, perVersionParent: List<Pair<Int, Any?>> = perVersion) {
        val per = perVersion.filter { it.second != null }.map { it.first }.sorted()
        if (per.isNotEmpty()) {
            jdks = per
        } else {
            // if no JDKs are set, we use the JDKs from the parent
            jdks = perVersionParent.filter { it.second != null }.map { it.first }.sorted()
        }
    }
}

class Relation {
    @JacksonXmlProperty(isAttribute = true)
    lateinit var name: String

    override fun toString() = objectToXml(this)
}

class XmlType : AbstractType<Example>() {

    @JacksonXmlProperty(isAttribute = true)
    lateinit var parameterType: String

    @JacksonXmlProperty(isAttribute = true)
    lateinit var fieldType: String

    @JacksonXmlProperty(isAttribute = true)
    var javaType: String? = null

    @JacksonXmlProperty(isAttribute = true)
    var contentType: String? = null

    @JacksonXmlProperty(isAttribute = true)
    var unsigned: Boolean? = null

    fun matchesJavaType(fullyQualifiedJavaType: String): Boolean {
        return javaType == fullyQualifiedJavaType
    }

    override fun toString() = objectToXml(this)
}

class XmlContentType : AbstractType<Example>() {

    var annotationBacking: String? = null

    @set:JacksonXmlProperty(isAttribute = true)
    var annotation: String
        get() = annotationBacking ?: "$annotationType($annotationValue)"
        set(value) {
            annotationBacking = value
        }

    @JacksonXmlProperty(isAttribute = true)
    var annotationType: String? = null

    @JacksonXmlProperty(isAttribute = true)
    var annotationValue: String? = null

    fun matchesAnnotation(annotationType: String, valueExpression: String, hasFrequency: Boolean = false): Boolean {
        assert(annotation.startsWith("jdk.jfr"))
        if (this.annotation.startsWith("jdk.jfr.$annotationType")) {
            val modAnn = this.annotation.substring(8).replace("(", ".").replace(")", "")
            if (hasFrequency) {
                return "$modAnn, jdk.jfr.Frequency" == valueExpression
            }
            return modAnn == valueExpression
        }
        return false
    }

    companion object {
        fun create(annotationType: String, valueExpression: String, hasFrequency: Boolean = false): XmlContentType {
            val (klass, field) = valueExpression.split(".")
            assert(klass == annotationType)
            return XmlContentType().apply {
                name = field.lowercase()
                annotation = "jdk.jfr.$annotationType($field)${if (hasFrequency) ", jdk.jfr.Frequency" else ""}"
            }
        }

        fun create(name: String, annotation: String, label: String): XmlContentType {
            return XmlContentType().apply {
                this.name = name
                this.annotation = annotation
            }
        }
    }

    override fun toString() = objectToXml(this)
}
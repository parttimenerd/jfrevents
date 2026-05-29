package me.bechberger.collector.site

import com.github.mustachejava.util.DecoratedCollection
import java.net.URI
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TreeMap
import java.util.concurrent.Callable
import me.bechberger.collector.xml.AbstractType
import me.bechberger.collector.xml.Event
import me.bechberger.collector.xml.Example
import me.bechberger.collector.xml.FieldType
import me.bechberger.collector.xml.Loader
import me.bechberger.collector.xml.Metadata
import me.bechberger.collector.xml.Type
import me.bechberger.collector.xml.XmlContentType
import me.bechberger.collector.xml.XmlType
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import picocli.CommandLine
import picocli.CommandLine.Command
import kotlin.io.path.exists
import kotlin.system.exitProcess

/**
 * @param fileNamePrefix the prefix for all but the index.html, the latter is named prefix.html
 * @param goatCounterUrls the url for goatcounter, if null, no goatcounter is used
 */
class Main(
    val target: Path,
    resourceFolder: Path? = null,
    val fileNamePrefix: String = "",
    val goatCounterUrls: List<String> = listOf(),
    val hideMissingDescriptions: Boolean = false
) {

    val versions = Loader.getVersions()

    /** LTS, last and current, the only versions that anyone would work with */
    val ltsVersions = versions.filter { it in setOf(11, 17, 21, 25) }
    val relevantVersions = (ltsVersions + listOf(versions.last(), versions.last() - 1)).distinct().sorted()
    val templating: Templating = Templating(resourceFolder)

    val versionToFileName = versions.associateWithTo(TreeMap<Int, String>()) {
        "$fileNamePrefix$it.html"
    }

    val indexFileName = if (fileNamePrefix.isEmpty()) "index.html" else "$fileNamePrefix.html"

    init {
        target.toFile().mkdirs()
        downloadBootstrapIfNeeded()
        templating.copyFromResources(target.resolve("css/style.css"), "template/style.css")
        templating.copyFromResources(target.resolve("img/sapmachine.svg"), "template/sapmachine.svg")
        downloadDependendenciesIfNeeded()
    }

    private fun downloadDependendenciesIfNeeded() {
        FILES_TO_DOWNLOAD.forEach { (url, targetFile) ->
            val path = target.resolve(targetFile).toFile()
            path.parentFile.mkdirs()
            URI(url).toURL().openStream().use { stream ->
                path.outputStream().use {
                    it.write(stream.readBytes())
                }
            }
        }
    }

    private fun downloadBootstrapIfNeeded() {
        if (!target.resolve("bootstrap").exists()) {
            URI(
                "https://github.com/twbs/bootstrap/releases/download/v$BOOTSTRAP_VERSION/" +
                        "bootstrap-$BOOTSTRAP_VERSION-dist.zip",
            ).toURL().openStream().use { stream ->
                target.resolve("bootstrap.zip").toFile().outputStream().use {
                    it.write(stream.readBytes())
                }
            }
            Runtime.getRuntime().exec(
                arrayOf(
                    "unzip",
                    target.resolve("bootstrap.zip").toString(),
                    "-d",
                    target.toString(),
                ),
            ).waitFor()
            target.resolve("bootstrap-$BOOTSTRAP_VERSION-dist").toFile().renameTo(target.resolve("bootstrap").toFile())
            target.resolve("bootstrap.zip").toFile().delete()
        }
    }

    inner class SupportedRelevantJDKScope(val version: Int, val file: String) {
        constructor(version: Int) : this(version, versionToFileName[version]!!)
    }

    data class SupportedRelevantJDKsScope(
        val versions: List<SupportedRelevantJDKScope>,
        val removedIn: SupportedRelevantJDKScope?,
        /** X is in all relevant JDKs <since>+ */
        val since: SupportedRelevantJDKScope?,
    )

    fun List<Int>.isSubList(other: List<Int>) = other.isNotEmpty() && subList(indexOf(other.first()), size) == other

    fun List<Int>.toSupportedRelevantJDKScopes(shorten: Boolean): SupportedRelevantJDKsScope {
        val relVersions = filter { it in relevantVersions }
        val relevantSinceVersion = if (relevantVersions.isSubList(relVersions)) relVersions.first() else null
        val sinceVersion = if (versions.isSubList(this)) first() else relevantSinceVersion
        val supportedRelevantJDKsScope = SupportedRelevantJDKsScope(
            (
                    (if (sinceVersion != null && sinceVersion != relVersions.first()) listOf(sinceVersion) else listOf()) +
                            relVersions
                    ).map {
                    SupportedRelevantJDKScope(
                        it,
                    )
                },
            if (last() != versions.last()) {
                SupportedRelevantJDKScope(
                    last() + 1,
                    versionToFileName[last() + 1]!!,
                )
            } else {
                null
            },
            if (shorten && sinceVersion != null) {
                SupportedRelevantJDKScope(sinceVersion)
            } else {
                null
            },
        )
        return supportedRelevantJDKsScope
    }

    fun formatJDKBadges(jdks: List<Int>, shorten: Boolean) =
        if (!shorten || !relevantVersions.all { it in jdks }) {
            templating.template(
                "jdk_badges.html",
                jdks.toSupportedRelevantJDKScopes(shorten),
            )
        } else {
            ""
        }

    class GraalVMInfo(
        val tag: String,
        val version: String,
        val url: String
    )

    class InfoScope(
        val version: Int,
        val isCurrent: Boolean,
        val year: Int,
        val versions: Array<VersionToFile>,
        val previousOneAfterLTS: Int?,
        val tag: String,
        val date: String,
        val hasAIGeneratedDescriptions: Boolean,
        val hasCodeContexts: Boolean,
        val url: String,
        val permanentURL: String?,
        val graalVMInfo: GraalVMInfo?,
        val goatCounterUlrs: List<String>,
    )

    data class MainScope(
        val info: InfoScope,
        val inner: List<String>,
    )

    data class VersionToFile(
        val version: Int,
        val fileName: String,
        val isCurrent: Boolean,
        val isBeta: Boolean,
        val isLTS: Boolean,
        val isRelevant: Boolean,
    )

    data class ForwardPageScope(
        val forwardUrl: String
    )

    fun createIndexPage() {
        templating.template("index.html", mapOf("forward" to versionToFileName[ltsVersions.last()]!!)).let {
            target.resolve(indexFileName).toFile().writeText(it)
        }
    }

    fun createPage(version: Int) {
        val metadata = Loader.loadVersion(version)
        println("${metadata.events.size} events (${metadata.events.count { it.examples.isNotEmpty() }} have examples)")
        val infoScope = InfoScope(
            version,
            version == ltsVersions.last(),
            LocalDate.now().year,
            versionToFileName.map {
                VersionToFile(
                    it.key,
                    it.value,
                    it.key == version,
                    it.key == versions.last() - 1,
                    it.key in ltsVersions,
                    relevantVersions.contains(it.key),
                )
            }.toTypedArray(),
            version.let {
                val lastLTS = ltsVersions.filter { v -> v < version }.maxOrNull()
                if (lastLTS == null || lastLTS == version - 1) {
                    null
                } else {
                    lastLTS + 1
                }
            },
            Loader.getSpecificVersion(version),
            LocalDate.ofInstant(Loader.getCreationDate(), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd-MMMM-yyyy")),
            metadata.events.any { it.aiGeneratedDescription != null },
            metadata.events.any { it.context.isNullOrEmpty().not() },
            metadata.url!!,
            if (metadata.permanentUrl == metadata.url!!) null else metadata.permanentUrl,
            metadata.graalVMInfo?.let { GraalVMInfo(it.tag, it.version, it.url) },
            goatCounterUrls
        )
        val html = templating.template(
            "main.html",
            MainScope(
                infoScope,
                body(metadata, infoScope),
            ),
        )
        target.resolve(versionToFileName[version]!!).toFile().writeText(html)
    }

    fun create() {
        createIndexPage()
        versions.forEach { createPage(it) }
    }

    data class SectionEntryScope(
        val name: String, val inner: String, val jdks: String,
        val inGraalOnly: Boolean, val inJDKOnly: Boolean,
        // description for search
        val description: String,
        val sectionTitle: String
    )

    data class SectionScope(
        val title: String, val entries: DecoratedCollection<SectionEntryScope>, val jdks: String,
        val inGraalOnly: Boolean, val inJDKOnly: Boolean
    )

    data class Flags(
        val isEnabled: Boolean,
        val isExperimental: Boolean,
        val isInternal: Boolean,
        val throttle: Boolean,
        val cutoff: Boolean,
        val enabledInConfigs: List<String>,
        val hasStartTime: Boolean,
        val hasDuration: Boolean,
        val hasStackTrace: Boolean,
        val hasThread: Boolean,
        val period: String?,
        val inGraal: Boolean,
        val inGraalOnly: Boolean,
    )

    data class TypeDescriptorScope(val name: String, val description: String? = null, val link: String? = null)

    data class FieldScope(
        val name: String,
        val type: TypeDescriptorScope,
        val contentType: TypeDescriptorScope?,
        val struct: Boolean,
        val array: Boolean,
        val experimental: Boolean,
        val transition: String?,
        val jdkBadges: String,
        val label: String,
        val description: String?,
        val additionalDescription: String?,
        val descriptionMissing: Boolean,
    )

    fun AbstractType<*>?.toLink(): String? {
        return this?.name?.let { name ->
            val link = name.replace(" ", "_").lowercase()
            if (this is Type) {
                "#$link"
            } else {
                "#$link"
            }
        }
    }

    /** for XMLType and Type */
    fun createTypeDescriptorScope(metadata: Metadata, type: String): TypeDescriptorScope {
        return (
                metadata.getSpecificType(type, metadata.types) ?: metadata.getSpecificType(
                    type,
                    metadata.xmlTypes,
                )
                )?.let {
                TypeDescriptorScope(
                    it.name,
                    null,
                    it.toLink(),
                )
            } ?: TypeDescriptorScope(type)
    }

    fun createContentTypeDescriptorScope(
        metadata: Metadata,
        type: String,
    ): TypeDescriptorScope? {
        return metadata.getSpecificType(type, metadata.xmlContentTypes)?.let {
            TypeDescriptorScope(
                it.name,
                null,
                it.toLink(),
            )
        }
    }

    fun createFieldScope(
        metadata: Metadata,
        field: me.bechberger.collector.xml.Field,
        parent: Type<*>,
    ): FieldScope {
        val descriptionAndLabelLength =
            field.label.length + (field.description?.length ?: 0) + (field.additionalDescription?.length ?: 0)
        return FieldScope(
            field.name,
            createTypeDescriptorScope(metadata, field.type),
            field.contentType?.let { createContentTypeDescriptorScope(metadata, it) },
            field.struct,
            field.array,
            field.experimental,
            field.transition.toString().lowercase(),
            if (parent.jdks != field.jdks) formatJDKBadges(field.jdks, shorten = true) else "",
            field.label,
            field.description,
            field.additionalDescription,
            !hideMissingDescriptions && descriptionAndLabelLength - 4 < field.name.length && field.type[0].isUpperCase(),
        )
    }

    /** the different types for XMLTypes */
    data class TypesTableScope(
        val parameterType: String = "",
        val fieldType: String = "",
        val javaType: String = "",
        val contentType: String = "",
        val contentTypeLink: String = "",
    )

    fun createTypeTableScope(
        metadata: Metadata,
        type: XmlType,
    ): TypesTableScope {
        return TypesTableScope(
            type.parameterType,
            type.fieldType,
            type.javaType ?: "",
            type.contentType ?: "",
            type.contentType?.let { contentType ->
                metadata.getSpecificType(contentType, metadata.xmlContentTypes).toLink()
            } ?: "",
        )
    }

    /** AbstractType and Type */
    data class TypeScope(
        val name: String,
        val label: String,
        val additionalDescription: String?,
        val descriptionMissing: Boolean,
        val jdkBadges: String,
        val fields: String?,
        val appearsIn: String,
        val examples: String,
        val typesTable: String?,
        val unsigned: Boolean,
        val annotation: String?,
    )

    fun formatTypesTable(metadata: Metadata, type: XmlType): String {
        return templating.template(
            "types_table.html",
            createTypeTableScope(metadata, type),
        )
    }

    fun createTypeScope(metadata: Metadata, type: AbstractType<*>): TypeScope {
        val descriptionAndLabelLength = type.label.length + (type.additionalDescription?.length ?: 0)
        return TypeScope(
            type.name,
            type.label,
            type.additionalDescription,
            !hideMissingDescriptions && descriptionAndLabelLength - 4 < type.name.length && type.name[0].isUpperCase(),
            formatJDKBadges(type.jdks, shorten = true),
            if (type is Type<*>) formatFields(metadata, type) else null,
            formatAppearsIn(metadata, type),
            formatTypeExamples(metadata, type),
            if (type is XmlType) formatTypesTable(metadata, type) else null,
            type is XmlType && (type.unsigned ?: false),
            if (type is XmlContentType) type.annotation else null,
        )
    }

    fun formatType(metadata: Metadata, type: AbstractType<*>): String {
        return templating.template(
            "type.html",
            createTypeScope(metadata, type),
        )
    }

    data class AppearsInScope(
        val appearsIn: DecoratedCollection<String>,
        val missesIn: DecoratedCollection<String>,
        val show: Boolean,
    )

    fun createAppearsInScope(metadata: Metadata, type: AbstractType<*>): AppearsInScope {
        val appearsIn = type.appearedIn.map { file -> metadata.getExampleName(file) }.toSet()
        val missesIn = metadata.exampleFiles.map { it.label }.toSet() - appearsIn

        return AppearsInScope(
            DecoratedCollection(
                appearsIn.sorted(),
            ),
            DecoratedCollection(
                missesIn.sorted(),
            ),
            show = missesIn.isNotEmpty() && appearsIn.isNotEmpty(),
        )
    }

    fun formatAppearsIn(metadata: Metadata, type: AbstractType<*>): String {
        val appearsInScope = createAppearsInScope(metadata, type)
        return templating.template(
            "appears_in.html",
            appearsInScope,
        )
    }

    data class ExampleScope(val name: String, val content: String = "", val platform: String? = null)

    data class ExamplesScope(val id: String, val examples: DecoratedCollection<ExampleScope>)

    data class TypeExamplesScope(
        val examples: String,
        val hasExamples: Boolean,
        val exampleSize: Int,
    )

    data class SimpleTypeLinkScope(val name: String, val link: String? = null)

    data class ObjectExampleEntryScope(
        val key: String,
        val value: String,
        val type: SimpleTypeLinkScope? = null,
        val contentType: SimpleTypeLinkScope? = null,
    )

    data class ExampleEntryScope(
        val depth: Int,
        val firstComplex: Boolean,
        val isTruncated: Boolean,
        var isNull: Boolean = false,
        var stringValue: String? = null,
        var arrayValue: DecoratedCollection<String>? = null,
        var objectValue: DecoratedCollection<ObjectExampleEntryScope>? = null,
    )

    fun formatExample(
        metadata: Metadata,
        example: Example,
        depth: Int = 0,
        firstComplex: Boolean = true,
    ): String {
        return templating.template(
            "example_entry.html",
            when (example.type) {
                FieldType.STRING -> ExampleEntryScope(depth, false, example.isTruncated).also {
                    it.stringValue = example.stringValue
                }

                FieldType.ARRAY -> ExampleEntryScope(depth, firstComplex, example.isTruncated).also {
                    example.arrayValue?.let { array ->
                        it.arrayValue = DecoratedCollection(
                            array.map { elem ->
                                formatExample(
                                    metadata,
                                    elem,
                                    depth + 1,
                                    false,
                                )
                            },
                        )
                    } ?: run {
                        it.isNull = true
                    }
                }

                FieldType.OBJECT -> ExampleEntryScope(depth, firstComplex, example.isTruncated).also {
                    example.objectValue?.let { obj ->
                        it.objectValue =
                            DecoratedCollection(
                                obj.entries.sortedBy { e -> e.key }.map { (k, v) ->
                                    val type =
                                        v.typeName?.let { t -> SimpleTypeLinkScope(t, metadata.getType(t).toLink()) }
                                    val contentType = v.contentTypeName?.let { c ->
                                        SimpleTypeLinkScope(
                                            c,
                                            metadata.getSpecificType(c, metadata.xmlContentTypes).toLink(),
                                        )
                                    }
                                    ObjectExampleEntryScope(
                                        k,
                                        formatExample(metadata, v, depth + 1, false),
                                        type,
                                        contentType,
                                    )
                                },
                            )
                    } ?: run {
                        it.isNull = true
                    }
                }

                FieldType.NULL -> ExampleEntryScope(depth, false, example.isTruncated).also { it.isNull = true }
            },
        )
    }

    fun createExampleScope(
        metadata: Metadata,
        example: Example,
    ): ExampleScope {
        val file = metadata.exampleFiles[example.exampleFile]
        return ExampleScope(file.label, formatExample(metadata, example), file.platform)
    }

    data class EventScope(
        val name: String,
        val categories: DecoratedCollection<String>,
        val stackFilter: DecoratedCollection<String>,
        val showStackFilter: Boolean,
        val description: String?,
        val additionalDescription: String?,
        val flags: Flags,
        val source: String?,
        val configurations: ConfigurationScope?,
        val jdkBadges: String,
        val allJDKs: List<Int>,
        val fields: String,
        val descriptionMissing: Boolean,
        val appearsIn: String,
        val examples: String,
        val aiGeneratedDescription: String,
        val codeContext: String,
    )

    data class ConfigurationScope(
        val headers: List<String>,
        val lastHeader: String,
        val rows: List<ConfigurationRowScope>,
    )

    data class ConfigurationRowScope(val name: String, val cells: List<String>)

    fun Event.topLevelCategory(): String {
        val cats = categories()
        if (cats.first() == "Java Virtual Machine") {
            if (cats.size > 1) {
                if (cats[1] == "GC" && cats.size > 2) {
                    return "JVM: GC: ${cats[2]}"
                }
                return "JVM: ${cats[1]}"
            }
            return "JVM"
        }
        if (cats.first() == "Java Application" && cats.last() == "Statistics") {
            return "Java Application Statistics"
        }
        return cats.first()
    }

    fun groupEventsByTopLevelCategory(metadata: Metadata): List<Pair<String, List<Event>>> {
        val sections = metadata.events
            .filter { it.name != "EveryChunkPeriodEvents" && it.name != "EndChunkPeriodEvents" }
            .groupBy { it.topLevelCategory() }
        return sections.map { (section, events) ->
            section to events
        }.sortedBy { it.first }
    }

    fun Metadata.getConfigurationName(id: Int): String {
        return when (configurations[id].label) {
            "Profiling" -> "profiling"
            "Continuous" -> "default"
            else -> configurations[id].label
        }
    }

    fun Metadata.getExampleName(id: Int): String {
        return exampleFiles[id].label
    }

    fun createConfigurationScope(
        metadata: Metadata,
        event: Event,
    ): ConfigurationScope? {
        val configs = event.configurations
        if (configs.isEmpty()) {
            return null
        }
        val configEntryNames = configs.flatMap { it -> it.settings.map { it.name } }.distinct().sorted()
        return ConfigurationScope(
            configEntryNames.dropLast(1),
            configEntryNames.last(),
            configs.map { config ->
                ConfigurationRowScope(
                    metadata.getConfigurationName(config.id) + " " + (
                            if (config.jdks != event.jdks) {
                                formatJDKBadges(
                                    config.jdks,
                                    shorten = true,
                                )
                            } else {
                                ""
                            }
                            ),
                    configEntryNames.map { name ->
                        config.settings.find { it.name == name }
                            ?.let {
                                it.value + " " + (
                                        if (it.jdks != config.jdks) {
                                            formatJDKBadges(
                                                it.jdks,
                                                shorten = true,
                                            )
                                        } else {
                                            ""
                                        }
                                        )
                            } ?: ""
                    },
                )
            },
        )
    }

    fun formatFields(
        metadata: Metadata,
        type: Type<*>,
        showEndTimeField: Boolean = true
    ): String {
        if (type.fields.isEmpty()) {
            return ""
        }
        return templating.template(
            "fields.html",
            mutableMapOf(
                "fields" to type.fields
                    .filter { it.name != "endTime" || showEndTimeField }
                    .map { createFieldScope(metadata, it, type) },
            ),
        )
    }

    fun formatExamples(metadata: Metadata, type: AbstractType<*>): String {
        if (type.examples.isEmpty()) {
            return ""
        }
        return templating.template(
            "examples.html",
            ExamplesScope(
                type.name + type.javaClass.name.length,
                DecoratedCollection(type.examples.map { createExampleScope(metadata, it) }),
            ),
        )
    }

    fun formatTypeExamples(metadata: Metadata, type: AbstractType<*>): String {
        if (type.examples.isEmpty()) {
            return ""
        }
        return templating.template(
            "type_examples.html",
            TypeExamplesScope(
                examples = formatExamples(metadata, type),
                exampleSize = type.examples.size,
                hasExamples = type.examples.size > 0,
            ),
        )
    }

    data class DescriptionSource(val name: String, val link: String)

    data class AIGeneratedDescriptionScope(val description: String, val sources: DecoratedCollection<DescriptionSource>)

    fun formatAIGeneratedDescription(metadata: Metadata, event: Event): String {
        if (event.aiGeneratedDescription == null) {
            return ""
        }
        val description = event.aiGeneratedDescription!!
        val htmlCode = HtmlRenderer.builder().build().render(Parser.builder().build().parse(description.description))
        return templating.template(
            "ai_gen_description.html",
            AIGeneratedDescriptionScope(
                htmlCode,
                DecoratedCollection(
                    description.sources.map {
                        DescriptionSource(
                            Path.of(it).fileName.toString(),
                            metadata.url + "/" + it,
                        )
                    },
                ),
            ),
        )
    }

    data class CodeContextScope(val licenseUrl: String, val contexts: List<SingleCodeContextScope>)

    data class SingleCodeContextScope(
        val url: String, val path: String,
        val startLine: Int, val endLine: Int,
        /** cpp or java */
        val language: String,
        val highlightedLines: List<Int>,
        val snippet: String
    )

    fun formatCodeContext(metadata: Metadata, event: Event): String {
        if (event.context.isNullOrEmpty()) {
            return ""
        }
        return templating.template(
            "code_context.html",
            CodeContextScope(
                "${metadata.url}/LICENSE",
                event.context!!.map { it ->
                    SingleCodeContextScope(
                        "${metadata.url}/${it.path}#L${it.startLine}-L${it.endLine}",
                        it.path,
                        it.startLine,
                        it.endLine,
                        if (it.path.endsWith(".java")) "java" else "cpp",
                        it.lines.map { line -> line + 1 },
                        it.snippet
                    )
                }
            )
        )
    }

    fun createEventScope(metadata: Metadata, event: Event): EventScope {
        return EventScope(
            name = event.name,
            categories = DecoratedCollection(event.categories()),
            stackFilter = DecoratedCollection(event.stackFilter),
            showStackFilter = event.stackFilter.isNotEmpty(),
            description = event.description,
            additionalDescription = event.additionalDescription,
            flags = Flags(
                isEnabled = event.enabled,
                isExperimental = event.experimental,
                isInternal = event.internal,
                throttle = event.throttle,
                cutoff = event.cutoff,
                enabledInConfigs = event.configurations.filter { conf ->
                    conf.settings.find { it.name == "enabled" }?.let { it.value != "false" } ?: true
                }.map { metadata.getConfigurationName(it.id) },
                hasStartTime = event.startTime,
                hasDuration = event.duration && !event.fakeDuration,
                hasThread = event.thread,
                hasStackTrace = event.stackTrace,
                period = event.period?.let {
                    when (it) {
                        "endChunk" -> "end of every chunk"
                        "beginChunk" -> "begin of every chunk"
                        "everyChunk" -> "every chunk"
                        else -> "every $it"
                    }
                },
                inGraal = event.isInJDKAndGraal() || event.isGraalOnly(),
                inGraalOnly = event.isGraalOnly(),
            ),
            source = event.source,
            configurations = createConfigurationScope(metadata, event),
            appearsIn = formatAppearsIn(metadata, event),
            jdkBadges = formatJDKBadges(event.jdks, shorten = false),
            allJDKs = event.jdks,
            fields = formatFields(metadata, event, event.duration && !event.fakeDuration),
            descriptionMissing = !hideMissingDescriptions && event.description.isNullOrBlank() && event.additionalDescription.isNullOrBlank(),
            examples = formatTypeExamples(metadata, event),
            aiGeneratedDescription = formatAIGeneratedDescription(metadata, event),
            codeContext = formatCodeContext(metadata, event)
        )
    }

    fun formatEvent(metadata: Metadata, event: Event): String {
        val eventScope = createEventScope(metadata, event)
        return templating.template("event.html", eventScope)
    }

    fun formatJDKList(jdks: List<Int>): String {
        return jdks.joinToString(", ")
    }

    fun createEventSection(
        metadata: Metadata,
        title: String,
        events: List<Event>,
    ): SectionScope {
        val combinedVariant = combinedVariant(events.map { it.includedInVariant })
        return SectionScope(
            title,
            DecoratedCollection(
                events.map {
                    SectionEntryScope(
                        it.name,
                        formatEvent(metadata, it),
                        formatJDKList(it.jdks),
                        it.isGraalOnly(),
                        it.isJDKOnly(),
                        (it.description ?: "") + (it.additionalDescription ?: ""),
                        title
                    )
                },
            ),
            formatJDKList(listOf(events.map { it.jdks.min() }.max())),
            combinedVariant == Event.GRAAL_ONLY,
            combinedVariant == Event.JDK_ONLY
        )
    }

    private fun combinedVariant(variants: List<String>): String {
        return if (variants.all { it == Event.GRAAL_ONLY }) {
            return Event.GRAAL_ONLY
        } else if (variants.all { it == Event.JDK_ONLY }) {
            return Event.JDK_ONLY
        } else {
            return Event.JDK_AND_GRAAL
        }
    }

    fun createTypeSection(
        metadata: Metadata,
        title: String,
        types: List<AbstractType<*>>,
    ): SectionScope {
        return SectionScope(
            title,
            DecoratedCollection(
                types.sortedBy { it.name }.map {
                    SectionEntryScope(it.name, formatType(metadata, it), formatJDKList(it.jdks), false, false,
                        (it.additionalDescription ?: ""), title)
                },
            ),
            formatJDKList(listOf(types.map { it.jdks.min() }.max())),
            inGraalOnly = false,
            inJDKOnly = false
        )
    }

    fun body(metadata: Metadata, infoScope: InfoScope): List<String> {
        val sections: MutableList<String> = mutableListOf(templating.template("intro.html", infoScope))
        sections.addAll(
            groupEventsByTopLevelCategory(metadata).map { (title, events) ->
                templating.template(
                    "section.html",
                    createEventSection(metadata, title, events),
                )
            },
        )
        sections.add(
            templating.template(
                "section.html",
                createTypeSection(metadata, "Types", metadata.types),
            ),
        )
        sections.add(
            templating.template(
                "section.html",
                createTypeSection(metadata, "XML Content Types", metadata.xmlContentTypes),
            ),
        )
        sections.add(
            templating.template(
                "section.html",
                createTypeSection(metadata, "XML Types", metadata.xmlTypes),
            ),
        )
        return sections
    }

    companion object {
        const val BOOTSTRAP_VERSION = "5.3.8"
        val FILES_TO_DOWNLOAD = mapOf(
            "https://raw.githubusercontent.com/afeld/bootstrap-toc/gh-pages/dist/bootstrap-toc.js" to "js/bootstrap-toc.js",
            "https://raw.githubusercontent.com/afeld/bootstrap-toc/gh-pages/dist/bootstrap-toc.css" to "css/bootstrap-toc.css",
            "https://cdn.jsdelivr.net/npm/jquery/dist/jquery.min.js" to "js/jquery.min.js",
            "https://cdn.jsdelivr.net/npm/anchor-js/anchor.min.js" to "js/anchor.min.js",
            "https://cdnjs.cloudflare.com/ajax/libs/prism/9000.0.1/prism.min.js" to "js/prism.min.js",
            "https://cdnjs.cloudflare.com/ajax/libs/prism/9000.0.1/themes/prism.min.css" to "css/prism.min.css",
            "https://cdnjs.cloudflare.com/ajax/libs/prism/9000.0.1/components/prism-cpp.min.js" to "js/prism-cpp.min.js",
            "https://cdnjs.cloudflare.com/ajax/libs/prism/9000.0.1/components/prism-c.min.js" to "js/prism-c.min.js",
            "https://cdnjs.cloudflare.com/ajax/libs/prism/9000.0.1/components/prism-java.min.js" to "js/prism-java.min.js",
            "https://cdnjs.cloudflare.com/ajax/libs/prism/9000.0.1/plugins/line-highlight/prism-line-highlight.min.css" to "css/prism-line-highlight.min.css",
            "https://cdnjs.cloudflare.com/ajax/libs/prism/9000.0.1/plugins/line-highlight/prism-line-highlight.min.js" to "js/prism-line-highlight.min.js",
            "https://cdnjs.cloudflare.com/ajax/libs/prism/9000.0.1/plugins/line-numbers/prism-line-numbers.min.css" to "css/prism-line-numbers.min.css",
            "https://cdnjs.cloudflare.com/ajax/libs/prism/9000.0.1/plugins/line-numbers/prism-line-numbers.min.js" to "js/prism-line-numbers.min.js",
        )
    }
}

@CommandLine.Command(
    name = "jfreventcollector-site",
    mixinStandardHelpOptions = true,
    description = ["JFR Event Collector Site Generator - Generate and manage JFR event documentation sites."],
    version = ["1.0"],
    subcommands = [GenerateCommand::class, CreateForwardCommand::class]
)
class MainCommand

@CommandLine.Command(
    name = "generate",
    description = ["Generate the JFR event documentation site."]
)
class GenerateCommand : Callable<Int> {

    @CommandLine.Parameters(index = "0", description = ["The target directory."])
    lateinit var target: Path

    @CommandLine.Option(names = ["-p", "--prefix"], description = ["The filename prefix."])
    var prefix: String? = null

    @CommandLine.Option(
        names = ["--goat-counter-url"],
        description = ["GoatCounter is an open source web analytics platform. This is the URL for GoatCounter."],
        arity = "0..*"
    )
    var goatCounterUrls: List<String> = listOf()

    @CommandLine.Option(
        names = ["--hide-missing-descriptions"],
        description = ["Hide the missing description text indicators."]
    )
    var hideMissingDescriptions: Boolean = false

    override fun call(): Int {
        Main(target, fileNamePrefix = prefix ?: "", goatCounterUrls = goatCounterUrls, hideMissingDescriptions = hideMissingDescriptions).create()
        return 0
    }
}

@CommandLine.Command(
    name = "create-forward",
    description = ["Create forward pages that redirect to a new base URL."]
)
class CreateForwardCommand : Runnable {

    @CommandLine.Parameters(index = "0", description = ["The target directory containing the site."])
    lateinit var folder: Path

    @CommandLine.Parameters(index = "1", description = ["The new base URL to forward to."], )
    lateinit var newBaseUrl: String

    @CommandLine.Option(names = ["--from-version"], description = ["The first Java version to create a forward page for."],
        defaultValue = "11")
    var fromVersion: Int? = null

    @CommandLine.Option(names = ["--to-version"], description = ["The last Java version to create a forward page for."],
        defaultValue = "35")
    var toVersion: Int? = null

    override fun run() {
        val folderFile = folder.toFile()
        if (!folderFile.exists()) {
            folderFile.mkdir()
        }

        // Normalize the base URL (remove trailing slash if present)
        val baseUrl = newBaseUrl.trimEnd('/')
        val fromVer = fromVersion!!
        val toVer = toVersion!!
        for (version in fromVer..toVer) {
            val pageName = "java${version}.html"
            createForwardPage("$version.html")
        }
        createForwardPage("index.html")
        println("Created forward pages in $folder to $baseUrl")
    }

    private fun createForwardPage(name: String) {
        println("Creating forward page for $name to $newBaseUrl/$name")
        val forwardUrl = newBaseUrl.trimEnd('/') + "/" + name
        val forwardHtml = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <script>
                location.replace("$forwardUrl" + location.search + location.hash);
              </script>
            </head>
            </html>
        """.trimIndent()
        folder.resolve(name).toFile().writeText(forwardHtml)
    }
}

fun main(args: Array<String>) {
    val exitCode = CommandLine(MainCommand()).execute(*args)
    exitProcess(exitCode)
}
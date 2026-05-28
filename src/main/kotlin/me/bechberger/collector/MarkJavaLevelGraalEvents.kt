package me.bechberger.collector

import me.bechberger.collector.xml.Metadata
import me.bechberger.collector.xml.readXmlAs
import java.nio.file.Paths
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Marks Java-level JDK JFR events supported by GraalVM Native Image "for free" — i.e. events
 * not listed in JfrEvent.java but inherited through the JDK event infrastructure.
 *
 * Called by releaser.py after GraalEventAdder processes the VM-level events.
 *
 * Usage: <metadata.xml> <EventName1> <EventName2> ... <result.xml>
 * The result path may equal the input path for an in-place update.
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        println("Usage: MarkJavaLevelGraalEvents <path to metadata.xml> <event1> ... <path to result xml file>")
        exitProcess(1)
    }
    val metadataPath = Paths.get(args[0])
    val outPath = Paths.get(args[args.size - 1])
    val eventNames = args.drop(1).dropLast(1)
    val metadata = metadataPath.readXmlAs(Metadata::class.java)
    var marked = 0
    for (name in eventNames) {
        val event = metadata.getEvent(name)
        if (event != null) {
            event.includedInGraal()
            marked++
        } else {
            println("Java-level Graal event '$name' not found in metadata — skipping")
        }
    }
    println("Marked $marked/${eventNames.size} Java-level events as supported by GraalVM Native Image")
    outPath.writeText(metadata.toString())
}

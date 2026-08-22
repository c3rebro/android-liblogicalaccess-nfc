package de.shansen.rfproject

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

class RfProjectReader(
    private val maxContainerBytes: Int = 16 * 1024 * 1024,
    private val maxXmlBytes: Int = 8 * 1024 * 1024,
    private val maxArchiveEntries: Int = 32
) {
    fun read(input: InputStream, sourceName: String? = null): RfProject {
        val containerBytes = input.use { it.readBounded(maxContainerBytes, "Project container") }
        val isArchive = sourceName?.endsWith(".rfprj", ignoreCase = true) == true || containerBytes.isZip()

        val xmlBytes = if (isArchive) {
            readProjectXmlFromArchive(containerBytes)
        } else {
            if (containerBytes.size > maxXmlBytes) {
                throw RfProjectFormatException("Project XML exceeds the maximum size of $maxXmlBytes bytes.")
            }
            containerBytes
        }

        return parseProject(
            normalizeLegacyXml(xmlBytes),
            if (isArchive) RfProjectContainer.RFPRJ_ARCHIVE else RfProjectContainer.XML,
            sourceName
        )
    }

    private fun readProjectXmlFromArchive(bytes: ByteArray): ByteArray {
        var entries = 0
        var projectXml: ByteArray? = null

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > maxArchiveEntries) {
                    throw RfProjectFormatException("Project archive contains more than $maxArchiveEntries entries.")
                }

                if (!entry.isDirectory && entry.name.substringAfterLast('/').equals("taskdatabase.xml", ignoreCase = true)) {
                    if (projectXml != null) {
                        throw RfProjectFormatException("Project archive contains multiple taskdatabase.xml entries.")
                    }
                    projectXml = zip.readBounded(maxXmlBytes, "taskdatabase.xml")
                }
                zip.closeEntry()
            }
        }

        return projectXml
            ?: throw RfProjectFormatException("RFIDGear project archive does not contain taskdatabase.xml.")
    }

    private fun parseProject(xmlBytes: ByteArray, container: RfProjectContainer, sourceName: String?): RfProject {
        rejectDangerousXml(xmlBytes)

        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                trySetFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                trySetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                trySetFeature("http://xml.org/sax/features/external-general-entities", false)
                trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
                trySetFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                try {
                    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                    setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                } catch (_: IllegalArgumentException) {
                    // Android XML providers do not all expose these JAXP attributes.
                }
            }

            val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
            val root = document.documentElement ?: throw RfProjectFormatException("Project XML has no root element.")
            val rootName = root.localName ?: root.nodeName
            if (rootName != "ChipTaskHandlerModel") {
                throw RfProjectFormatException("Unexpected project root '$rootName'; expected ChipTaskHandlerModel.")
            }

            val manifestVersion = root.directChild("ManifestVersion")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            val taskCollection = root.directChild("TaskCollection")
            val tasks = taskCollection?.childElements()?.mapIndexed { index, element ->
                RfProjectTask(
                    position = index,
                    elementName = element.localName ?: element.nodeName,
                    typeName = resolveTypeName(element),
                    node = element.toProjectNode()
                )
            } ?: emptyList()

            return RfProject(
                manifestVersion = manifestVersion,
                tasks = tasks,
                container = container,
                rootElement = rootName,
                sourceName = sourceName
            )
        } catch (e: RfProjectFormatException) {
            throw e
        } catch (e: Exception) {
            throw RfProjectFormatException("Unable to parse RFIDGear project XML: ${e.message}", e)
        }
    }

    private fun resolveTypeName(element: Element): String {
        val xsiType = element.getAttributeNS(XSI_NAMESPACE, "type")
            .takeIf { it.isNotBlank() }
            ?: element.attributes.asSequence()
                .firstOrNull { (it.localName ?: it.nodeName.substringAfter(':')) == "type" }
                ?.nodeValue
                ?.takeIf { it.isNotBlank() }

        return xsiType?.substringAfter(':')
            ?: (element.localName ?: element.nodeName).takeUnless { it == "anyType" }
            ?: "Unknown"
    }

    private fun normalizeLegacyXml(bytes: ByteArray): ByteArray {
        val xml = String(bytes, StandardCharsets.UTF_8)
            .replace("AuthenticationError", "AuthFailure")
        return xml.toByteArray(StandardCharsets.UTF_8)
    }

    private fun rejectDangerousXml(bytes: ByteArray) {
        val text = String(bytes, StandardCharsets.UTF_8)
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) {
            throw RfProjectFormatException("DOCTYPE and ENTITY declarations are not allowed in project files.")
        }
    }

    private fun DocumentBuilderFactory.trySetFeature(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: Exception) {
            // rejectDangerousXml() provides an additional fail-closed check for DTD/entity payloads.
        }
    }

    private fun Element.directChild(name: String): Element? = childElements().firstOrNull {
        (it.localName ?: it.nodeName) == name
    }

    private fun Element.childElements(): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) result += node as Element
        }
        return result
    }

    private fun Element.toProjectNode(): RfProjectNode {
        val attrs = attributes.asSequence().map {
            RfProjectAttribute(
                name = it.localName ?: it.nodeName.substringAfter(':'),
                qualifiedName = it.nodeName,
                namespaceUri = it.namespaceURI,
                value = it.nodeValue ?: ""
            )
        }.toList()

        val children = childElements().map { it.toProjectNode() }
        val directText = buildString {
            val nodes = childNodes
            for (i in 0 until nodes.length) {
                val child = nodes.item(i)
                if (child.nodeType == Node.TEXT_NODE || child.nodeType == Node.CDATA_SECTION_NODE) {
                    append(child.nodeValue ?: "")
                }
            }
        }.trim().takeIf { it.isNotEmpty() }

        return RfProjectNode(
            name = localName ?: nodeName,
            qualifiedName = nodeName,
            namespaceUri = namespaceURI,
            attributes = attrs,
            children = children,
            text = directText
        )
    }

    private fun org.w3c.dom.NamedNodeMap.asSequence(): Sequence<Node> = sequence {
        for (i in 0 until length) yield(item(i))
    }

    private fun InputStream.readBounded(limit: Int, label: String): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw RfProjectFormatException("$label exceeds the maximum size of $limit bytes.")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun ByteArray.isZip(): Boolean = size >= 4 &&
        this[0] == 0x50.toByte() && this[1] == 0x4b.toByte() &&
        (this[2] == 0x03.toByte() || this[2] == 0x05.toByte() || this[2] == 0x07.toByte())

    private companion object {
        const val XSI_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance"
    }
}

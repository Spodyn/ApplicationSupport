package com.unifiedsupportinbox.storage.internal;

import static com.unifiedsupportinbox.storage.internal.AttachmentPolicyViolationException.Code.BLOCKED_ARCHIVE;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Validates bounded, macro-free Office packages without extracting archive entries. */
enum OfficeAttachmentPackage {
    WORD("docx", "wordprocessingml.document", "word/document.xml", "document", "wordprocessingml"),
    EXCEL("xlsx", "spreadsheetml.sheet", "xl/workbook.xml", "workbook", "spreadsheetml"),
    POWERPOINT("pptx", "presentationml.presentation", "ppt/presentation.xml", "presentation", "presentationml");

    private static final String CONTENT_TYPES = "[Content_Types].xml";
    private static final String ROOT_RELATIONSHIPS = "_rels/.rels";
    private static final String TYPES_NAMESPACE = "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String RELATIONSHIPS_NAMESPACE = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final long MAX_EXPANDED = 250L * 1024 * 1024;
    private static final long MAX_METADATA = 2L * 1024 * 1024;
    private static final int MAX_ENTRIES = 1000;

    private final String extension;
    private final String mime;
    private final String mainPart;
    private final String rootElement;
    private final String namespaceSuffix;

    OfficeAttachmentPackage(String extension, String mimeSuffix, String mainPart, String rootElement, String namespaceSuffix) {
        this.extension = extension;
        this.mime = "application/vnd.openxmlformats-officedocument." + mimeSuffix;
        this.mainPart = mainPart;
        this.rootElement = rootElement;
        this.namespaceSuffix = namespaceSuffix;
    }

    String mime() {
        return mime;
    }

    static OfficeAttachmentPackage forExtension(String extension) {
        for (var office : values()) if (office.extension.equals(extension)) return office;
        return null;
    }

    static boolean isOfficeMime(String mime) {
        for (var office : values()) if (office.mime.equals(mime)) return true;
        return false;
    }

    void validate(InputStream input) throws IOException, AttachmentPolicyViolationException {
        Path file = Files.createTempFile("usi-office-", ".zip");
        try {
            try (var target = Files.newOutputStream(file)) {
                byte[] buffer = new byte[8192];
                long copied = 0;
                for (int count; (count = input.read(buffer)) != -1;) {
                    copied += count;
                    if (copied > AttachmentFilePolicy.MAX_FILE_BYTES) throw rejected();
                    target.write(buffer, 0, count);
                }
            }
            try (var archive = new ZipFile(file.toFile())) {
                verifyEntries(archive);
                verifyPackageXml(archive);
            } catch (ZipException | XMLStreamException invalid) {
                throw rejected();
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void verifyEntries(ZipFile archive) throws IOException, AttachmentPolicyViolationException {
        if (archive.size() > MAX_ENTRIES) throw rejected();
        Set<String> names = new HashSet<>();
        long expanded = 0;
        byte[] buffer = new byte[8192];
        var entries = archive.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (!safePath(name) || !names.add(lower) || activeContent(lower)
                    || entry.getSize() < 0 || entry.getSize() > MAX_EXPANDED - expanded
                    || ((CONTENT_TYPES.equals(name) || lower.endsWith(".rels")) && entry.getSize() > MAX_METADATA)) {
                throw rejected();
            }
            if (entry.isDirectory()) {
                if (entry.getSize() != 0) throw rejected();
                continue;
            }
            try (InputStream stream = archive.getInputStream(entry)) {
                long bytes = 0;
                var crc = new CRC32();
                int prefix = stream.readNBytes(buffer, 0, 4);
                if (nestedArchive(buffer, prefix)) throw rejected();
                bytes += prefix;
                crc.update(buffer, 0, prefix);
                for (int count; (count = stream.read(buffer)) != -1;) {
                    bytes += count;
                    if (bytes > entry.getSize()) throw rejected();
                    crc.update(buffer, 0, count);
                }
                if (bytes != entry.getSize() || crc.getValue() != entry.getCrc()) throw rejected();
                expanded += bytes;
            }
        }
    }

    private void verifyPackageXml(ZipFile archive) throws IOException, XMLStreamException, AttachmentPolicyViolationException {
        requireType(archive, CONTENT_TYPES);
        ZipEntry root = archive.getEntry(ROOT_RELATIONSHIPS);
        if (root == null || !relationships(archive, root)) throw rejected();
        var entries = archive.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            if (!entry.getName().equals(ROOT_RELATIONSHIPS) && entry.getName().toLowerCase(Locale.ROOT).endsWith(".rels")) relationships(archive, entry);
        }
        ZipEntry part = archive.getEntry(mainPart);
        if (part == null) throw rejected();
        try (var stream = archive.getInputStream(part)) {
            var xml = xmlFactory().createXMLStreamReader(stream);
            try {
                xml.nextTag();
                String namespace = xml.getNamespaceURI();
                if (!rootElement.equals(xml.getLocalName())
                        || !Set.of("http://schemas.openxmlformats.org/" + namespaceSuffix + "/2006/main",
                                "http://purl.oclc.org/ooxml/" + namespaceSuffix + "/main").contains(namespace == null ? "" : namespace)) {
                    throw rejected();
                }
                while (xml.hasNext()) xml.next();
            } finally {
                xml.close();
            }
        }
    }

    private void requireType(ZipFile archive, String name) throws IOException, XMLStreamException, AttachmentPolicyViolationException {
        ZipEntry part = archive.getEntry(name);
        if (part == null) throw rejected();
        boolean matches = false;
        try (var stream = archive.getInputStream(part)) {
            var xml = xmlFactory().createXMLStreamReader(stream);
            try {
                xml.nextTag();
                if (!"Types".equals(xml.getLocalName()) || !TYPES_NAMESPACE.equals(xml.getNamespaceURI())) throw rejected();
                while (xml.hasNext()) {
                    if (xml.next() != XMLStreamConstants.START_ELEMENT) continue;
                    String contentType = attribute(xml, "ContentType");
                    if (activeContent(contentType.toLowerCase(Locale.ROOT))) throw rejected();
                    if ("Override".equals(xml.getLocalName()) && ("/" + mainPart).equals(attribute(xml, "PartName"))) {
                        if (matches || !contentType.equals(mime + ".main+xml")) throw rejected();
                        matches = true;
                    }
                }
            } finally {
                xml.close();
            }
        }
        if (!matches) throw rejected();
    }

    private boolean relationships(ZipFile archive, ZipEntry entry) throws IOException, XMLStreamException, AttachmentPolicyViolationException {
        boolean hasMain = false;
        try (var stream = archive.getInputStream(entry)) {
            var xml = xmlFactory().createXMLStreamReader(stream);
            try {
                xml.nextTag();
                if (!"Relationships".equals(xml.getLocalName()) || !RELATIONSHIPS_NAMESPACE.equals(xml.getNamespaceURI())) {
                    throw rejected();
                }
                while (xml.hasNext()) {
                    if (xml.next() != XMLStreamConstants.START_ELEMENT) continue;
                    String type = attribute(xml, "Type");
                    String target = attribute(xml, "Target");
                    String mode = attribute(xml, "TargetMode");
                    if (activeContent(type.toLowerCase(Locale.ROOT))
                            || ("External".equalsIgnoreCase(mode) && !type.endsWith("/hyperlink"))) {
                        throw rejected();
                    }
                    if ((type.equals("http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument")
                            || type.equals("http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument"))
                            && (mainPart.equals(target) || ("/" + mainPart).equals(target))) {
                        hasMain = true;
                    }
                }
            } finally {
                xml.close();
            }
        }
        return hasMain;
    }

    private static XMLInputFactory xmlFactory() {
        var factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> { throw new XMLStreamException("External XML is disabled"); });
        return factory;
    }

    private static String attribute(XMLStreamReader xml, String name) {
        String value = xml.getAttributeValue(null, name);
        return value == null ? "" : value;
    }

    private static boolean safePath(String name) {
        if (name.isEmpty() || name.length() > 512 || name.startsWith("/") || name.contains("\\") || name.contains(":")
                || name.contains("%") || name.codePoints().anyMatch(Character::isISOControl)) return false;
        for (String segment : name.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
        }
        return true;
    }

    private static boolean activeContent(String value) {
        return value.contains("vbaproject") || value.contains("macroenabled") || value.contains("activex")
                || value.contains("oleobject") || value.contains("/embeddings/") || value.endsWith("/package")
                || (value.endsWith(".bin") && !value.matches("(?:word|xl|ppt)/printersettings/printersettings[0-9]+[.]bin"))
                || value.matches(".*[.](?:exe|dll|msi|js|vbs|ps1|cmd|bat|jar|zip|rar|7z|gz|html?|hta)$");
    }

    private static boolean nestedArchive(byte[] bytes, int count) {
        if (count < 2) return false;
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        if ((first == 0x1f && second == 0x8b) || (first == 'M' && second == 'Z')) return true;
        if (count < 4) return false;
        return (first == 'P' && second == 'K' && ((bytes[2] == 3 && bytes[3] == 4) || (bytes[2] == 5 && bytes[3] == 6)
                || (bytes[2] == 7 && bytes[3] == 8))) || (first == 0x7f && second == 'E' && bytes[2] == 'L' && bytes[3] == 'F');
    }

    private static AttachmentPolicyViolationException rejected() {
        return new AttachmentPolicyViolationException(BLOCKED_ARCHIVE);
    }
}

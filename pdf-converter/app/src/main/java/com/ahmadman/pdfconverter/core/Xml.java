package com.ahmadman.pdfconverter.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** XML escaping for the writers and small DOM helpers for the readers. */
public final class Xml {

    private Xml() {
    }

    /** Escapes text and drops characters that are illegal in XML 1.0. */
    public static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                default:
                    if (c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF)
                            || (c >= 0xE000 && c <= 0xFFFD)) {
                        sb.append(c);
                    } else if (Character.isHighSurrogate(c) && i + 1 < s.length()
                            && Character.isLowSurrogate(s.charAt(i + 1))) {
                        sb.append(c).append(s.charAt(++i));
                    }
                    // other control characters are dropped
            }
        }
        return sb.toString();
    }

    public static Document parse(byte[] data) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(data));
    }

    /** Direct child elements with the given local name ({@code null} = all). */
    public static List<Element> children(Node parent, String localName) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE
                    && (localName == null || localName.equals(local(n)))) {
                out.add((Element) n);
            }
        }
        return out;
    }

    public static Element child(Node parent, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equals(local(n))) {
                return (Element) n;
            }
        }
        return null;
    }

    /** Follows a path of child local names, e.g. {@code path(sp, "spPr", "xfrm", "off")}. */
    public static Element path(Node start, String... names) {
        Node cur = start;
        for (String n : names) {
            cur = child(cur, n);
            if (cur == null) {
                return null;
            }
        }
        return (Element) cur;
    }

    /** All descendant elements with the local name, in document order. */
    public static List<Element> descendants(Node parent, String localName) {
        List<Element> out = new ArrayList<>();
        collect(parent, localName, out);
        return out;
    }

    private static void collect(Node n, String localName, List<Element> out) {
        NodeList nl = n.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node c = nl.item(i);
            if (c.getNodeType() == Node.ELEMENT_NODE) {
                if (localName.equals(local(c))) {
                    out.add((Element) c);
                }
                collect(c, localName, out);
            }
        }
    }

    public static String local(Node n) {
        String l = n.getLocalName();
        if (l == null) {
            l = n.getNodeName();
            int colon = l.indexOf(':');
            if (colon >= 0) {
                l = l.substring(colon + 1);
            }
        }
        return l;
    }

    /** Attribute by local name regardless of prefix ({@code w:val}, {@code r:embed} ...). */
    public static String attr(Element e, String localName) {
        if (e == null) {
            return null;
        }
        org.w3c.dom.NamedNodeMap map = e.getAttributes();
        for (int i = 0; i < map.getLength(); i++) {
            Node a = map.item(i);
            if (localName.equals(local(a))) {
                return a.getNodeValue();
            }
        }
        return null;
    }

    public static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    /** Relationship attribute such as {@code r:id} / {@code r:embed} (not a plain {@code id}). */
    public static String rel(Element e, String localName) {
        if (e == null) {
            return null;
        }
        String v = e.getAttributeNS(REL_NS, localName);
        if (v != null && !v.isEmpty()) {
            return v;
        }
        org.w3c.dom.NamedNodeMap map = e.getAttributes();
        for (int i = 0; i < map.getLength(); i++) {
            Node a = map.item(i);
            String name = a.getNodeName();
            if (name.indexOf(':') > 0 && localName.equals(local(a))) {
                return a.getNodeValue();
            }
        }
        return null;
    }

    public static long attrLong(Element e, String localName, long def) {
        String v = attr(e, localName);
        if (v == null) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}

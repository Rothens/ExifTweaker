package me.rothens.gpsexif.metadata;

import me.rothens.gpsexif.util.SafeXml;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the place fields of an XMP packet: {@code photoshop:City/State/Country} and
 * {@code Iptc4xmpCore:Location/CountryCode}, the ones Lightroom, digiKam and the IPTC standard use. Everything else in
 * the packet is kept as it is.
 */
public final class XmpPlace {

    static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    static final String PHOTOSHOP = "http://ns.adobe.com/photoshop/1.0/";
    static final String IPTC_CORE = "http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/";
    private static final String X = "adobe:ns:meta/";
    static final String EXIF = "http://ns.adobe.com/exif/1.0/";

    private enum Field {
        CITY(PHOTOSHOP, "City"),
        STATE(PHOTOSHOP, "State"),
        COUNTRY(PHOTOSHOP, "Country"),
        SUBLOCATION(IPTC_CORE, "Location"),
        COUNTRY_CODE(IPTC_CORE, "CountryCode");

        final String ns;
        final String name;

        Field(String ns, String name) {
            this.ns = ns;
            this.name = name;
        }

        String value(Place p) {
            return switch (this) {
                case CITY -> p.city();
                case STATE -> p.state();
                case COUNTRY -> p.country();
                case SUBLOCATION -> p.sublocation();
                case COUNTRY_CODE -> p.countryCode();
            };
        }
    }

    private XmpPlace() {
    }

    /** The place in an XMP packet, or {@code null} if it has none (or isn't readable). */
    public static Place read(String xmp) {
        if (null == xmp || xmp.isBlank()) {
            return null;
        }
        try {
            Document doc = parse(xmp);
            String[] values = new String[Field.values().length];
            for (Element description : descriptions(doc)) {
                for (Field f : Field.values()) {
                    if (null == values[f.ordinal()]) {
                        values[f.ordinal()] = value(description, f);
                    }
                }
            }
            Place place = new Place(values[Field.SUBLOCATION.ordinal()], values[Field.CITY.ordinal()],
                    values[Field.STATE.ordinal()], values[Field.COUNTRY.ordinal()],
                    values[Field.COUNTRY_CODE.ordinal()]);
            return place.isEmpty() ? null : place;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The packet with the place replaced; {@link Place#NONE} removes it. {@code xmp} may be {@code null} (no packet
     * yet), then a new one is made.
     *
     * @throws IOException if the existing packet isn't valid XMP
     */
    public static String apply(String xmp, Place place) throws IOException {
        return apply(xmp, place, false);
    }

    /** Also removes the GPS properties ({@code exif:GPS...}) if {@code removeGps}. */
    public static String apply(String xmp, Place place, boolean removeGps) throws IOException {
        Document doc = null == xmp || xmp.isBlank() ? newPacket() : parse(xmp);
        List<Element> descriptions = descriptions(doc);
        Element rdf = (Element) doc.getElementsByTagNameNS(RDF, "RDF").item(0);
        if (null == rdf) {
            throw new IOException("The XMP data has no rdf:RDF element");
        }
        for (Element description : descriptions) {
            for (Field f : Field.values()) {
                description.removeAttributeNS(f.ns, f.name);
                for (Element child : children(description, f)) {
                    description.removeChild(child);
                }
            }
            if (removeGps) {
                NamedNodeMap attributes = description.getAttributes();
                for (int i = attributes.getLength() - 1; i >= 0; i--) {
                    Attr a = (Attr) attributes.item(i);
                    if (EXIF.equals(a.getNamespaceURI()) && a.getLocalName().startsWith("GPS")) {
                        description.removeAttributeNode(a);
                    }
                }
                List<Element> gps = new ArrayList<>();
                for (Node n = description.getFirstChild(); null != n; n = n.getNextSibling()) {
                    if (n instanceof Element e && EXIF.equals(e.getNamespaceURI())
                            && e.getLocalName().startsWith("GPS")) {
                        gps.add(e);
                    }
                }
                gps.forEach(description::removeChild);
            }
        }
        if (!place.isEmpty()) {
            Element description = doc.createElementNS(RDF, "rdf:Description");
            description.setAttributeNS(RDF, "rdf:about", "");
            description.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:photoshop", PHOTOSHOP);
            description.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:Iptc4xmpCore", IPTC_CORE);
            for (Field f : Field.values()) {
                String value = f.value(place);
                if (null != value) {
                    Element e = doc.createElementNS(f.ns, (f.ns.equals(PHOTOSHOP) ? "photoshop:" : "Iptc4xmpCore:")
                            + f.name);
                    e.setTextContent(value);
                    description.appendChild(e);
                }
            }
            rdf.appendChild(description);
        }
        return serialize(doc);
    }

    private static Document parse(String xmp) throws IOException {
        try {
            // Some writers put junk (padding, a BOM) around the packet
            String text = xmp.strip();
            if (text.startsWith("﻿")) {
                text = text.substring(1);
            }
            return SafeXml.newDocumentBuilder(true).parse(new InputSource(new StringReader(text)));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("The XMP data isn't valid XML: " + e.getMessage(), e);
        }
    }

    private static Document newPacket() throws IOException {
        return parse("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                + "<x:xmpmeta xmlns:x=\"" + X + "\"><rdf:RDF xmlns:rdf=\"" + RDF + "\"/></x:xmpmeta>"
                + "<?xpacket end=\"w\"?>");
    }

    private static List<Element> descriptions(Document doc) {
        List<Element> list = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagNameNS(RDF, "Description");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            // Only top-level descriptions (structures nest their own)
            if (e.getParentNode() instanceof Element parent && RDF.equals(parent.getNamespaceURI())
                    && "RDF".equals(parent.getLocalName())) {
                list.add(e);
            }
        }
        return list;
    }

    private static List<Element> children(Element description, Field f) {
        List<Element> list = new ArrayList<>();
        for (Node n = description.getFirstChild(); null != n; n = n.getNextSibling()) {
            if (n instanceof Element e && f.ns.equals(e.getNamespaceURI()) && f.name.equals(e.getLocalName())) {
                list.add(e);
            }
        }
        return list;
    }

    private static String value(Element description, Field f) {
        NamedNodeMap attributes = description.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr a = (Attr) attributes.item(i);
            if (f.ns.equals(a.getNamespaceURI()) && f.name.equals(a.getLocalName()) && !a.getValue().isBlank()) {
                return a.getValue().strip();
            }
        }
        for (Element e : children(description, f)) {
            String text = e.getTextContent();
            if (null != text && !text.isBlank()) {
                return text.strip();
            }
        }
        return null;
    }

    private static String serialize(Document doc) throws IOException {
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter out = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString();
        } catch (TransformerException e) {
            throw new IOException("Couldn't write the XMP data: " + e.getMessage(), e);
        }
    }
}

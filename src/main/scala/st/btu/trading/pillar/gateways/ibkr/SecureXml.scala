package st.btu.trading.pillar.gateways.ibkr

import java.io.StringReader
import javax.xml.stream.{XMLInputFactory, XMLStreamConstants, XMLStreamReader}

// XML from outside the process is read with document type declarations and external entities
// switched off, so that a document cannot make the reader fetch a file or a URL, or expand an
// entity into more text than it carries.
private[ibkr] object SecureXml {

  def reader(xml: String): XMLStreamReader = {
    val factory = XMLInputFactory.newFactory()
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.box(false))
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.box(false))
    factory.createXMLStreamReader(new StringReader(xml))
  }

  // What `read` makes of each start element, in document order. `read` runs while the reader is at
  // the element, so it can read the element's name, attributes and text; the reader moves on once
  // the element is taken.
  def readStartElements[A](reader: XMLStreamReader)(read: XMLStreamReader => A): Iterator[A] =
    Iterator
      .continually(reader)
      .takeWhile(_.hasNext)
      .map(_.next())
      .collect { case XMLStreamConstants.START_ELEMENT => read(reader) }
}

package io.devreload.mybatis;

import java.io.ByteArrayInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/**
 * MyBatis에 넘기기 전에 mapper XML에서 읽어 두는 최소한의 정보.
 *
 * @param namespace      {@code <mapper namespace>} 값
 * @param declaresCache  파일이 {@code <cache>} 요소를 선언하는지 여부
 */
record MapperXml(String namespace, boolean declaresCache) {

    private static final ErrorHandler THROWING = new ErrorHandler() {
        @Override
        public void warning(SAXParseException ex) {
        }

        @Override
        public void error(SAXParseException ex) throws SAXParseException {
            throw ex;
        }

        @Override
        public void fatalError(SAXParseException ex) throws SAXParseException {
            throw ex;
        }
    };

    /**
     * mapper 정보를 반환한다. 내용이 MyBatis mapper 문서가 아니면 {@code null}을 반환한다.
     *
     * @throws Exception 내용이 well-formed XML이 아닌 경우
     */
    static MapperXml read(byte[] content) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(THROWING);

        Element root = builder.parse(new ByteArrayInputStream(content)).getDocumentElement();
        if (!"mapper".equals(root.getTagName())) {
            return null;
        }
        String namespace = root.getAttribute("namespace").trim();
        if (namespace.isEmpty()) {
            return null;
        }
        boolean declaresCache = root.getElementsByTagName("cache").getLength() > 0;
        return new MapperXml(namespace, declaresCache);
    }
}

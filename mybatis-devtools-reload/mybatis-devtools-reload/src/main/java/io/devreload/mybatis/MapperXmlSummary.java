package io.devreload.mybatis;

import java.io.ByteArrayInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/**
 * {@code XMLMapperBuilder}로 파싱하기 전에 mapper XML에서 뽑아 두는 요약.
 * 어느 {@code Configuration}에 적용할지(namespace)와 파싱 전에 무엇을 지울지(namespace, cache 선언 여부)를
 * 정하는 데 필요한 값만 담는다.
 * <p>
 * DTD를 받지 않고 검증도 하지 않는 최소 설정의 DOM 파서로 한 번만 훑으므로 네트워크 없이 빠르게 끝난다.
 * 문법 검증은 뒤이은 {@code XMLMapperBuilder} 파싱에 맡긴다.
 * <p>
 * @param namespace      {@code <mapper namespace>} 값
 * @param declaresCache  파일이 {@code <cache>} 요소를 선언하는지 여부
 */
record MapperXmlSummary(String namespace, boolean declaresCache) {

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
     * 내용을 가볍게 훑어 mapper 요약을 만든다. 루트가 {@code <mapper>}가 아니거나 namespace가 비어 있으면
     * 리로드 대상이 아니라는 뜻으로 {@code null}을 반환한다.
     *
     * @throws Exception 내용이 well-formed XML이 아닌 경우
     */
    static MapperXmlSummary probe(byte[] content) throws Exception {
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
        // MyBatis(XMLMapperBuilder)는 namespace를 trim하지 않고 그대로 registry 키 접두사로 쓴다.
        // 혹시나 공백이 섞인 namespace가 있으면 기존 항목을 찾지 못해 중복 등록 오류가 나므로 같은 규칙을 따른다.
        String namespace = root.getAttribute("namespace");
        if (namespace.isEmpty()) {
            return null;
        }
        boolean declaresCache = root.getElementsByTagName("cache").getLength() > 0;
        return new MapperXmlSummary(namespace, declaresCache);
    }
}

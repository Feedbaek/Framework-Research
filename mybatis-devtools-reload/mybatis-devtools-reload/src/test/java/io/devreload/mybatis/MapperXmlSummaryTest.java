package io.devreload.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class MapperXmlSummaryTest {

    @Test
    void readsNamespaceAndCacheWithoutFetchingDtd() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper"><cache/></mapper>
                """;
        MapperXmlSummary summary = MapperXmlSummary.probe(xml.getBytes(StandardCharsets.UTF_8));

        assertThat(summary).isEqualTo(new MapperXmlSummary("com.example.UserMapper", true));
    }

    @Test
    void returnsNullForOtherXml() throws Exception {
        assertThat(MapperXmlSummary.probe("<configuration/>".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(MapperXmlSummary.probe("<mapper/>".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(MapperXmlSummary.probe("<mapper namespace=\"\"/>".getBytes(StandardCharsets.UTF_8))).isNull();
    }

    @Test
    void keepsNamespaceVerbatimLikeMyBatis() throws Exception {
        MapperXmlSummary summary = MapperXmlSummary.probe("<mapper namespace=\" com.example.UserMapper \"/>"
                .getBytes(StandardCharsets.UTF_8));

        assertThat(summary.namespace()).isEqualTo(" com.example.UserMapper ");
    }

    @Test
    void cacheRefIsNotCacheDeclaration() throws Exception {
        MapperXmlSummary summary = MapperXmlSummary.probe("<mapper namespace=\"a.B\"><cache-ref namespace=\"c.D\"/></mapper>"
                .getBytes(StandardCharsets.UTF_8));

        assertThat(summary.declaresCache()).isFalse();
    }

    @Test
    void rejectsMalformedXml() {
        assertThatThrownBy(() -> MapperXmlSummary.probe("<mapper namespace=\"a\"><select".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(Exception.class);
    }
}

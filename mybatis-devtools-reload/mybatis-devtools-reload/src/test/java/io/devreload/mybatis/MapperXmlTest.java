package io.devreload.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class MapperXmlTest {

    @Test
    void readsNamespaceAndCacheWithoutFetchingDtd() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper"><cache/></mapper>
                """;
        MapperXml info = MapperXml.read(xml.getBytes(StandardCharsets.UTF_8));

        assertThat(info).isEqualTo(new MapperXml("com.example.UserMapper", true));
    }

    @Test
    void returnsNullForOtherXml() throws Exception {
        assertThat(MapperXml.read("<configuration/>".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(MapperXml.read("<mapper/>".getBytes(StandardCharsets.UTF_8))).isNull();
    }

    @Test
    void rejectsMalformedXml() {
        assertThatThrownBy(() -> MapperXml.read("<mapper namespace=\"a\"><select".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(Exception.class);
    }
}

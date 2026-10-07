package io.devreload.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MapperReloaderTest {

    private static final String NS = TestUserMapper.class.getName();
    private static final String RESOURCE = "file [/src/main/resources/mapper/TestUserMapper.xml]";

    private Configuration configuration;
    private SqlSessionFactory factory;
    private MapperReloader reloader;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver", url, "sa", "");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50))");
            s.execute("INSERT INTO users VALUES (1, 'kim'), (2, 'lee'), (3, 'park')");
        }

        configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(TestUserMapper.class); // mybatis-spring처럼 애노테이션 statement를 먼저 등록한다
        load("file [/build/resources/main/mapper/TestUserMapper.xml]", mapper("ORDER BY id", ""));
        factory = new SqlSessionFactoryBuilder().build(configuration);
        reloader = new MapperReloader(configuration, getClass().getClassLoader());
    }

    @Test
    void reloadReplacesXmlStatementAndKeepsAnnotationStatement() {
        assertThat(names()).containsExactly("kim", "lee", "park");

        int count = reloader.reload(RESOURCE, bytes(mapper("ORDER BY id DESC", "")));

        assertThat(count).isEqualTo(1);
        assertThat(names()).containsExactly("park", "lee", "kim");
        assertThat(countUsers()).isEqualTo(3);
    }

    @Test
    void reloadIsRepeatable() {
        reloader.reload(RESOURCE, bytes(mapper("ORDER BY id DESC", "")));
        reloader.reload(RESOURCE, bytes(mapper("ORDER BY name", "")));
        reloader.reload(RESOURCE, bytes(mapper("ORDER BY id", "")));

        assertThat(names()).containsExactly("kim", "lee", "park");
    }

    @Test
    void reloadWithCacheSqlFragmentResultMapAndSelectKeyTwice() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="%s">
                  <cache/>
                  <resultMap id="nameMap" type="map"><result column="name" property="name"/></resultMap>
                  <sql id="cols">name</sql>
                  <select id="findNames" resultType="string">SELECT <include refid="cols"/> FROM users %s</select>
                  <select id="findRows" resultMap="nameMap">SELECT name FROM users</select>
                  <insert id="insertUser" parameterType="map">
                    <selectKey keyProperty="id" resultType="int" order="BEFORE">SELECT MAX(id) + 1 FROM users</selectKey>
                    INSERT INTO users (id, name) VALUES (#{id}, #{name})
                  </insert>
                </mapper>
                """;
        reloader.reload(RESOURCE, bytes(xml.formatted(NS, "ORDER BY id DESC")));
        int count = reloader.reload(RESOURCE, bytes(xml.formatted(NS, "ORDER BY name DESC")));

        assertThat(count).isEqualTo(4); // findNames, findRows, insertUser, insertUser!selectKey
        assertThat(names()).containsExactly("park", "lee", "kim");
        assertThat(configuration.hasKeyGenerator(NS + ".insertUser!selectKey")).isTrue();
        assertThat(configuration.getCache(NS)).isNotNull();
    }

    @Test
    void removedStatementDisappears() {
        reloader.reload(RESOURCE, bytes(mapper("ORDER BY id", """
                <select id="findFirst" resultType="string">SELECT name FROM users WHERE id = 1</select>
                """)));
        assertThat(configuration.hasStatement(NS + ".findFirst")).isTrue();

        reloader.reload(RESOURCE, bytes(mapper("ORDER BY id", "")));

        assertThat(configuration.hasStatement(NS + ".findFirst", false)).isFalse();
    }

    @Test
    void sqlErrorInXmlStructureKeepsPreviousState() {
        String broken = mapper("ORDER BY id DESC", "<select id=\"bad\" resultMap=\"doesNotExist\">SELECT 1</select>");

        assertThatThrownBy(() -> reloader.reload(RESOURCE, bytes(broken)))
                .isInstanceOf(MapperReloadException.class)
                .hasMessageContaining("previous SQL kept");

        assertThat(names()).containsExactly("kim", "lee", "park");
        assertThat(configuration.hasStatement(NS + ".bad", false)).isFalse();
        assertThat(configuration.getIncompleteStatements()).isEmpty();
    }

    @Test
    void malformedXmlKeepsPreviousState() {
        assertThatThrownBy(() -> reloader.reload(RESOURCE, bytes("<mapper namespace=\"" + NS + "\"><select")))
                .isInstanceOf(MapperReloadException.class);

        assertThat(names()).containsExactly("kim", "lee", "park");
    }

    @Test
    void knowsLoadedNamespaceOnly() {
        assertThat(reloader.knows(NS)).isTrue();
        assertThat(reloader.knows("com.example.Unknown")).isFalse();
    }

    private List<String> names() {
        try (SqlSession session = factory.openSession()) {
            return session.getMapper(TestUserMapper.class).findNames();
        }
    }

    private long countUsers() {
        try (SqlSession session = factory.openSession()) {
            return session.getMapper(TestUserMapper.class).countUsers();
        }
    }

    private void load(String resource, String xml) {
        new XMLMapperBuilder(new ByteArrayInputStream(bytes(xml)), configuration, resource,
                configuration.getSqlFragments()).parse();
    }

    private static String mapper(String orderBy, String extra) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="%s">
                  <select id="findNames" resultType="string">SELECT name FROM users %s</select>
                  %s
                </mapper>
                """.formatted(NS, orderBy, extra);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}

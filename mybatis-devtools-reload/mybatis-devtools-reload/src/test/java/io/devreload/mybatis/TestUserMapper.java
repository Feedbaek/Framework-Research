package io.devreload.mybatis;

import java.util.List;

import org.apache.ibatis.annotations.Select;

public interface TestUserMapper {

    // XML에 정의돼 있다
    List<String> findNames();

    // 애노테이션으로 정의돼 있다. XML을 리로드해도 남아 있어야 한다
    @Select("SELECT COUNT(*) FROM users")
    long countUsers();
}

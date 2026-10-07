package io.devreload.sample;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    /** mapper/UserMapper.xml에 정의돼 있다. 앱이 실행 중일 때 SQL을 수정해 보면 된다. */
    List<User> findAll();

    /** 애노테이션으로 정의돼 있다. XML을 리로드해도 그대로 유지된다. */
    @Select("SELECT COUNT(*) FROM users")
    long count();
}

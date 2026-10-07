package io.devreload.sample;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {

    private final UserMapper userMapper;

    public UserController(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @GetMapping("/users")
    public List<User> users() {
        return userMapper.findAll();
    }

    @GetMapping("/users/count")
    public Map<String, Long> count() {
        return Map.of("count", userMapper.count());
    }
}

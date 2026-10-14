package io.devreload.mybatis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code mybatis-reload.*} 아래의 설정.
 */
@ConfigurationProperties(prefix = MapperReloadProperties.PREFIX)
public class MapperReloadProperties {

    public static final String PREFIX = "mybatis-reload";

    static final List<String> DEFAULT_RESTART_EXCLUDE = List.of("mapper/**", "mappers/**", "**/*Mapper.xml");

    /**
     * mapper XML 리로드를 켠다. 기본값은 꺼짐이며, 로컬 개발 환경에서만 켠다.
     */
    private boolean enabled = false;

    /**
     * mapper XML 변경을 감시할 소스 디렉터리. 상대 경로는 작업 디렉터리를 기준으로 해석한다. 저장만 해도 빌드
     * 없이 반영되도록 build output이 아니라 소스 디렉터리를 감시한다.
     */
    private List<String> watchDirs = new ArrayList<>(List.of("src/main/resources"));

    /**
     * 감시 디렉터리 기준 상대 경로에 적용하는 Ant 패턴(예: mapper/**). 일치하는 XML 파일만 감시하고, 일치하는
     * 파일이 있을 수 없는 디렉터리는 감시 대상으로 등록하지 않는다. 비어 있으면 모든 XML 파일을 감시한다.
     */
    private List<String> watchInclude = new ArrayList<>();

    /**
     * 마지막 파일 이벤트 이후 리로드하기 전까지 기다리는 시간.
     */
    private Duration debounce = Duration.ofMillis(300);

    /**
     * {@link #devtoolsRestartExclude}를 {@code spring.devtools.restart.additional-exclude}에 추가해, build
     * output의 mapper 변경이 DevTools 재시작을 일으키지 않게 한다.
     */
    private boolean devtoolsIntegration = true;

    /**
     * DevTools 재시작을 일으키면 안 되는 mapper 파일의 classpath 상대 Ant 패턴.
     */
    private List<String> devtoolsRestartExclude = new ArrayList<>(DEFAULT_RESTART_EXCLUDE);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getWatchDirs() {
        return watchDirs;
    }

    public void setWatchDirs(List<String> watchDirs) {
        this.watchDirs = watchDirs;
    }

    public List<String> getWatchInclude() {
        return watchInclude;
    }

    public void setWatchInclude(List<String> watchInclude) {
        this.watchInclude = watchInclude;
    }

    public Duration getDebounce() {
        return debounce;
    }

    public void setDebounce(Duration debounce) {
        this.debounce = debounce;
    }

    public boolean isDevtoolsIntegration() {
        return devtoolsIntegration;
    }

    public void setDevtoolsIntegration(boolean devtoolsIntegration) {
        this.devtoolsIntegration = devtoolsIntegration;
    }

    public List<String> getDevtoolsRestartExclude() {
        return devtoolsRestartExclude;
    }

    public void setDevtoolsRestartExclude(List<String> devtoolsRestartExclude) {
        this.devtoolsRestartExclude = devtoolsRestartExclude;
    }
}

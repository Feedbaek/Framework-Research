package com.example.reload;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 재로딩 설정.
 */
@ConfigurationProperties("reload")
public class ReloadProperties {

	/**
	 * 재로딩 엔진 사용 여부. false이면 엔진이 꺼지고 부모의 MVC auto-configuration이 그대로 동작한다.
	 */
	private boolean enabled = true;

	/**
	 * 자식 클래스로더 URL(디렉터리 또는 jar). 상대 경로는 작업 디렉터리 기준이다. 비우면
	 * {@code @SpringBootApplication} 클래스가 있는 출력 디렉터리를 쓴다.
	 */
	private List<String> classpath = new ArrayList<>();

	/**
	 * 자식 context에서 컴포넌트 스캔할 패키지. 비우면 {@code @SpringBootApplication} 클래스의 패키지를 쓴다.
	 */
	private List<String> basePackages = new ArrayList<>();

	/**
	 * business-packages 안에서도 부모에 유지할 공통 타입과 인프라 패키지. 업무 영역 밖의 클래스와
	 * {@code @SpringBootApplication} 클래스는 항상 부모 소유다.
	 */
	private List<String> parentPackages = new ArrayList<>();

	/** 명시적인 빠른 재로딩 영역. 비어 있으면 일반 Boot 구성 + 전체 재시작만 사용한다. */
	private List<String> businessPackages = new ArrayList<>();

	/** 클래스 출력 외에 감시할 리소스 디렉터리 또는 빌드 설정 파일. */
	private List<String> watchPaths = new ArrayList<>();

	public List<String> getBusinessPackages() {
		return this.businessPackages;
	}

	public void setBusinessPackages(List<String> businessPackages) {
		this.businessPackages = businessPackages;
	}

	public List<String> getWatchPaths() {
		return this.watchPaths;
	}

	public void setWatchPaths(List<String> watchPaths) {
		this.watchPaths = watchPaths;
	}

	public boolean isHybrid() {
		return !this.businessPackages.isEmpty();
	}

	/**
	 * 클래스패스 디렉터리 폴링 주기.
	 */
	private Duration pollInterval = Duration.ofSeconds(1);

	/**
	 * 변경이 멈춘 뒤 재로딩을 시작하기까지 기다리는 시간.
	 */
	private Duration quietPeriod = Duration.ofMillis(400);

	/**
	 * 이전 세대의 진행 중 요청을 기다리는 최대 시간. 지나면 강제로 dispose한다.
	 */
	private Duration drainTimeout = Duration.ofSeconds(30);

	/**
	 * 변경으로 치지 않을 파일 패턴(Ant 스타일, 감시 디렉터리 기준 상대 경로).
	 */
	private List<String> excludePatterns = new ArrayList<>(List.of("**/*.log"));

	/**
	 * 재로딩을 시작하는 방식.
	 */
	private final Trigger trigger = new Trigger();

	/**
	 * 설정 배치 규칙 검사.
	 */
	private final PlacementCheck placementCheck = new PlacementCheck();

	public Trigger getTrigger() {
		return this.trigger;
	}

	public PlacementCheck getPlacementCheck() {
		return this.placementCheck;
	}

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public List<String> getClasspath() {
		return this.classpath;
	}

	public void setClasspath(List<String> classpath) {
		this.classpath = classpath;
	}

	public List<String> getParentPackages() {
		return this.parentPackages;
	}

	public void setParentPackages(List<String> parentPackages) {
		this.parentPackages = parentPackages;
	}

	public List<String> getBasePackages() {
		return this.basePackages;
	}

	public void setBasePackages(List<String> basePackages) {
		this.basePackages = basePackages;
	}

	public Duration getPollInterval() {
		return this.pollInterval;
	}

	public void setPollInterval(Duration pollInterval) {
		this.pollInterval = pollInterval;
	}

	public Duration getQuietPeriod() {
		return this.quietPeriod;
	}

	public void setQuietPeriod(Duration quietPeriod) {
		this.quietPeriod = quietPeriod;
	}

	public Duration getDrainTimeout() {
		return this.drainTimeout;
	}

	public void setDrainTimeout(Duration drainTimeout) {
		this.drainTimeout = drainTimeout;
	}

	public List<String> getExcludePatterns() {
		return this.excludePatterns;
	}

	public void setExcludePatterns(List<String> excludePatterns) {
		this.excludePatterns = excludePatterns;
	}

	/**
	 * 재로딩 트리거 설정.
	 */
	public static class Trigger {

		/**
		 * 재로딩을 시작하는 방식. watch: 클래스패스 디렉터리 변경 감지, api: HTTP API 호출, both: 둘 다.
		 */
		private Mode mode = Mode.WATCH;

		/**
		 * 재로딩 API 경로(mode가 api 또는 both일 때). POST는 재로딩, GET은 상태 조회. 인증이 없으므로 개발
		 * 환경에서만 켠다.
		 */
		private String apiPath = "/_reload";

		public Mode getMode() {
			return this.mode;
		}

		public void setMode(Mode mode) {
			this.mode = mode;
		}

		public String getApiPath() {
			return this.apiPath;
		}

		public void setApiPath(String apiPath) {
			this.apiPath = apiPath;
		}

	}

	/**
	 * 설정 배치 규칙(인프라 설정은 부모, Advisor·BeanPostProcessor 기반 {@code @Enable*}만 자식) 검사 설정.
	 */
	public static class PlacementCheck {

		/**
		 * 설정 배치 규칙 위반을 경고할지. 부모 쪽은 첫 세대를 만들 때, 자식 쪽은 위반 목록이 바뀐 세대마다 경고한다.
		 */
		private boolean enabled = true;

		/**
		 * 자식 설정에 추가로 허용할 애너테이션의 완전한 클래스 이름. Advisor나 BeanPostProcessor를 등록하는
		 * {@code @Enable*}(사내 라이브러리 등)만 넣는다.
		 */
		private List<String> allowedChildAnnotations = new ArrayList<>();

		public boolean isEnabled() {
			return this.enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public List<String> getAllowedChildAnnotations() {
			return this.allowedChildAnnotations;
		}

		public void setAllowedChildAnnotations(List<String> allowedChildAnnotations) {
			this.allowedChildAnnotations = allowedChildAnnotations;
		}

	}

	/**
	 * 재로딩 트리거 방식.
	 */
	public enum Mode {

		/**
		 * 클래스패스 디렉터리의 변경을 감지해 재로딩한다.
		 */
		WATCH(true, false),

		/**
		 * 재로딩 API가 호출될 때만 재로딩한다.
		 */
		API(false, true),

		/**
		 * 변경 감지와 API를 모두 쓴다.
		 */
		BOTH(true, true);

		private final boolean watch;

		private final boolean api;

		Mode(boolean watch, boolean api) {
			this.watch = watch;
			this.api = api;
		}

		public boolean isWatch() {
			return this.watch;
		}

		public boolean isApi() {
			return this.api;
		}

	}

}

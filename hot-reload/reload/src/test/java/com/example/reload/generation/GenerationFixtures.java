package com.example.reload.generation;

import com.example.reload.watch.ChangePlanner;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 기존 컨텍스트 생명주기 테스트에 시나리오를 설치한다. 이 단계는 변경 분류 테스트가 아니다.
 * 업무/인프라 변경 정책은 HybridReloadTest와 ChangePlannerTest에서 공개 API로 별도 검증한다.
 */
final class GenerationFixtures {
	static ReloadResult install(GenerationManager manager) {
		ChangePlanner planner = (ChangePlanner) ReflectionTestUtils.getField(manager, "changePlanner");
		return manager.buildGeneration(planner.snapshot(), java.util.List.of("test fixture installation"));
	}
}

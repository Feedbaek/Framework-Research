package com.example.reload.generation;

/**
 * 재로딩 한 번의 결과.
 * <p>
 * 실패 원인은 문자열로만 담는다. 예외 객체는 스택 정보로 자식 클래스를 붙잡으므로 보관하지 않는다.
 *
 * @param reloaded 새 세대로 교체했는지
 * @param generation 호출이 끝난 뒤 현재 세대 번호(실패하면 기존 세대 번호)
 * @param previousGeneration 호출 전 세대 번호(없으면 0)
 * @param durationMillis 새 세대를 만드는 데 걸린 시간
 * @param error 실패 원인 요약(성공하면 {@code null})
 */
public record ReloadResult(boolean reloaded, int generation, int previousGeneration, long durationMillis,
		String error) {

}

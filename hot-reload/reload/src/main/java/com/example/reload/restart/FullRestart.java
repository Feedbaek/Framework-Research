package com.example.reload.restart;

/** 외부 감독자 또는 임베딩 환경이 제공하는 전체 재시작 경계. */
public interface FullRestart {
	boolean available();
	/** 비동기 재시작을 예약한다. 중복 요청도 멱등적으로 수락한다. */
	void request();
}

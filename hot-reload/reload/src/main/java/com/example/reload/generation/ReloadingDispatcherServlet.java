package com.example.reload.generation;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 부모에 등록되는 유일한 servlet. 요청을 현재 세대의 {@code DispatcherServlet}으로 넘긴다.
 * <p>
 * 요청마다 세대를 {@link Generation#acquire() acquire}해서 처리 중에는 그 세대가 dispose되지 않게
 * 하고, 처리하는 동안 TCCL을 그 세대의 클래스로더로 바꾼다. 비동기 요청은 비동기 처리가 끝날 때
 * release한다.
 */
public class ReloadingDispatcherServlet extends HttpServlet {

	private static final String GENERATION_ATTRIBUTE = ReloadingDispatcherServlet.class.getName() + ".GENERATION";

	private final transient GenerationManager manager;

	public ReloadingDispatcherServlet(GenerationManager manager) {
		this.manager = manager;
	}

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		if (request.getDispatcherType() == DispatcherType.ASYNC
				&& request.getAttribute(GENERATION_ATTRIBUTE) instanceof Generation generation) {
			// 비동기 결과 재디스패치: 처음 요청을 받은 세대가 이어서 처리해야 한다.
			// acquire는 최초 디스패치에서 이미 했고 release는 AsyncListener가 한다.
			dispatch(generation, request, response);
			return;
		}
		Generation generation = acquireCurrent();
		if (generation == null) {
			response.setHeader("Retry-After", "1");
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			return;
		}
		boolean asyncStarted = false;
		try {
			dispatch(generation, request, response);
			asyncStarted = request.isAsyncStarted();
			if (asyncStarted) {
				request.setAttribute(GENERATION_ATTRIBUTE, generation);
				request.getAsyncContext().addListener(new ReleasingAsyncListener(generation));
			}
		}
		finally {
			if (!asyncStarted) {
				generation.release();
			}
		}
	}

	private Generation acquireCurrent() {
		// 읽은 세대가 막 retire된 경우를 위해 한 번 재시도한다.
		for (int attempt = 0; attempt < 2; attempt++) {
			Generation generation = this.manager.current();
			if (generation != null && generation.acquire()) {
				return generation;
			}
		}
		return null;
	}

	private void dispatch(Generation generation, HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		Thread thread = Thread.currentThread();
		ClassLoader previousTccl = thread.getContextClassLoader();
		thread.setContextClassLoader(generation.classLoader());
		try {
			generation.dispatcher().service(request, response);
		}
		finally {
			thread.setContextClassLoader(previousTccl);
		}
	}

	/**
	 * 비동기 처리가 끝나면(완료, 오류, 타임아웃) 세대를 한 번만 release한다.
	 */
	private static final class ReleasingAsyncListener implements AsyncListener {

		private final Generation generation;

		private final AtomicBoolean released = new AtomicBoolean();

		ReleasingAsyncListener(Generation generation) {
			this.generation = generation;
		}

		@Override
		public void onComplete(AsyncEvent event) {
			release();
		}

		@Override
		public void onTimeout(AsyncEvent event) {
			// 타임아웃 뒤에도 오류 처리 디스패치/완료가 이어지므로 onComplete에서 release한다.
		}

		@Override
		public void onError(AsyncEvent event) {
			// onComplete가 뒤따른다.
		}

		@Override
		public void onStartAsync(AsyncEvent event) {
			// startAsync가 다시 호출되면 리스너 목록이 비워지므로 다시 등록한다.
			event.getAsyncContext().addListener(this);
		}

		private void release() {
			if (this.released.compareAndSet(false, true)) {
				this.generation.release();
			}
		}

	}

}

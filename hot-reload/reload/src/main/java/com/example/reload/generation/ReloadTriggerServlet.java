package com.example.reload.generation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 재로딩 API. 부모에 {@code reload.trigger.api-path}로 등록된다({@code reload.trigger.mode}가 api 또는 both).
 * <ul>
 * <li>{@code POST}: 재로딩하고 결과를 돌려준다. 성공 200, 새 세대가 뜨지 못하면 500(기존 세대 유지).</li>
 * <li>{@code GET}: 현재 세대 번호와 실패 횟수를 돌려준다.</li>
 * </ul>
 * 세대를 거치지 않는 별도 servlet이라 자식 세대가 없거나 실패한 상태에서도 호출할 수 있다. 인증이 없으므로
 * 개발 환경에서만 켠다.
 */
public class ReloadTriggerServlet extends HttpServlet {

	private final transient GenerationManager manager;

	public ReloadTriggerServlet(GenerationManager manager) {
		this.manager = manager;
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
		ReloadResult result = this.manager.reloadWithResult();
		StringBuilder json = new StringBuilder("{");
		json.append("\"reloaded\":").append(result.reloaded());
		json.append(",\"generation\":").append(result.generation());
		json.append(",\"previousGeneration\":").append(result.previousGeneration());
		json.append(",\"durationMillis\":").append(result.durationMillis());
		json.append(",\"failedReloads\":").append(this.manager.failedReloads());
		json.append(",\"error\":").append((result.error() != null) ? quote(result.error()) : "null");
		json.append('}');
		write(response, result.reloaded() ? HttpServletResponse.SC_OK : HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
				json);
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		StringBuilder json = new StringBuilder("{");
		json.append("\"mode\":").append(quote(this.manager.triggerMode().name().toLowerCase(Locale.ROOT)));
		json.append(",\"generation\":").append(this.manager.currentGenerationId());
		json.append(",\"failedReloads\":").append(this.manager.failedReloads());
		json.append('}');
		write(response, HttpServletResponse.SC_OK, json);
	}

	private static void write(HttpServletResponse response, int status, CharSequence json) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setHeader("Cache-Control", "no-store");
		response.getWriter().write(json.toString());
	}

	private static String quote(String value) {
		StringBuilder quoted = new StringBuilder("\"");
		for (char ch : value.toCharArray()) {
			switch (ch) {
				case '"' -> quoted.append("\\\"");
				case '\\' -> quoted.append("\\\\");
				case '\n' -> quoted.append("\\n");
				case '\r' -> quoted.append("\\r");
				case '\t' -> quoted.append("\\t");
				default -> {
					if (ch < 0x20) {
						quoted.append(String.format("\\u%04x", (int) ch));
					}
					else {
						quoted.append(ch);
					}
				}
			}
		}
		return quoted.append('"').toString();
	}

}

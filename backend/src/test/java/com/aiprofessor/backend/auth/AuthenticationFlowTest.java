package com.aiprofessor.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiprofessor.backend.TestcontainersConfiguration;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "aiprofessor.auth.login.allowed-origins=http://localhost:5173",
                "aiprofessor.auth.refresh.allowed-origins=http://localhost:5173",
                "aiprofessor.auth.logout.allowed-origins=http://localhost:5173"
        }
)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
@ExtendWith(OutputCaptureExtension.class)
class AuthenticationFlowTest {

    private static final String ORIGIN = "http://localhost:5173";
    private static final String OLD_PASSWORD = "test-original-pw";
    private static final String NEW_PASSWORD = "test-replaced-pw";
    private static final String LOGIN_PATH = "/auth/login";
    private static final String REFRESH_PATH = "/auth/refresh";
    private static final String LOGOUT_PATH = "/auth/logout";
    private static final String PROFILE_PATH = "/users/me";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private UUID userId;

    @AfterEach
    void cleanUp() {
        client.close();
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void 실제_JWT로_가입부터_비밀번호_변경과_로그아웃까지_처리한다(CapturedOutput output)
            throws Exception {
        String email = signUp();
        HttpResponse<String> initialLogin = send(loginRequest(email, OLD_PASSWORD, ORIGIN), 200);
        assertCredentialedCors(initialLogin);
        TokenPair first = tokensFrom(initialLogin);

        JsonNode initialProfile = dataFrom(send(
                bearerRequest(PROFILE_PATH, first.accessToken()).GET(), 200));
        assertThat(initialProfile.path("userId").asString()).isEqualTo(userId.toString());
        assertThat(initialProfile.path("email").asString()).isEqualTo(email);
        assertThat(initialProfile.has("passwordHash")).isFalse();

        JsonNode renamedProfile = dataFrom(send(jsonRequest(
                bearerRequest(PROFILE_PATH, first.accessToken()),
                "PATCH",
                Map.of("displayName", "새 학습자")), 200));
        assertThat(renamedProfile.path("displayName").asString()).isEqualTo("새 학습자");

        TokenPair rotated = tokensFrom(send(refreshRequest(first.refreshToken()), 200));
        assertThat(rotated.refreshToken().equals(first.refreshToken())).isFalse();

        HttpResponse<String> passwordChange = send(jsonRequest(
                bearerRequest(PROFILE_PATH + "/password", rotated.accessToken()),
                "PATCH",
                Map.of("currentPassword", OLD_PASSWORD, "newPassword", NEW_PASSWORD)), 204);
        assertThat(passwordChange.body()).isEmpty();
        assertErrorCode(send(loginRequest(email, OLD_PASSWORD, ORIGIN), 401), "AUTH_0001");
        assertRefreshRejected(rotated.refreshToken());

        TokenPair relogin = tokensFrom(send(loginRequest(email, NEW_PASSWORD, ORIGIN), 200));
        JsonNode persistedProfile = dataFrom(send(
                bearerRequest(PROFILE_PATH, relogin.accessToken()).GET(), 200));
        assertThat(persistedProfile.path("displayName").asString()).isEqualTo("새 학습자");

        HttpResponse<String> logout = send(bearerRequest(LOGOUT_PATH, relogin.accessToken())
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.COOKIE, "refresh_token=" + relogin.refreshToken())
                .POST(HttpRequest.BodyPublishers.noBody()), 204);
        assertThat(logout.body()).isEmpty();
        assertCookieExpired(logout);
        assertRefreshRejected(relogin.refreshToken());

        List<String> sensitiveValues = List.of(
                email, OLD_PASSWORD, NEW_PASSWORD,
                first.accessToken(), first.refreshToken(),
                rotated.accessToken(), rotated.refreshToken(),
                relogin.accessToken(), relogin.refreshToken()
        );
        String logs = output.getAll();
        assertThat(sensitiveValues.stream().noneMatch(logs::contains))
                .as("인증 처리 로그에 이메일, 비밀번호, 토큰 또는 Cookie 값이 없어야 한다")
                .isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "http://localhost:5999"})
    void 허용되지_않은_Origin은_올바른_자격_증명으로도_로그인할_수_없다(String origin)
            throws Exception {
        String email = signUp();

        HttpResponse<String> response = send(loginRequest(email, OLD_PASSWORD, origin), 403);

        assertErrorCode(response, "AUTH_0009");
        assertThat(response.headers().firstValue(HttpHeaders.SET_COOKIE)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Long.class, userId))
                .isZero();
    }

    @Test
    void 허용된_로그인_사전_요청은_자격_증명이나_Cookie_발급_없이_응답한다() throws Exception {
        HttpResponse<String> response = send(request(LOGIN_PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.CONTENT_TYPE)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()), 200);

        assertCredentialedCors(response);
        assertThat(response.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS))
                .contains("POST");
        assertThat(response.headers().firstValue(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    private String signUp() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        HttpResponse<String> response = send(jsonRequest(request("/users"), "POST", Map.of(
                "email", email, "password", OLD_PASSWORD, "displayName", "학습자"
        )), 201);
        userId = UUID.fromString(dataFrom(response).path("userId").asString());
        return email;
    }

    private HttpRequest.Builder loginRequest(String email, String password, String origin) {
        HttpRequest.Builder request = request(LOGIN_PATH);
        if (origin != null) {
            request.header(HttpHeaders.ORIGIN, origin);
        }
        return jsonRequest(request, "POST", Map.of("email", email, "password", password));
    }

    private HttpRequest.Builder refreshRequest(String refreshToken) {
        return request(REFRESH_PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.COOKIE, "refresh_token=" + refreshToken)
                .POST(HttpRequest.BodyPublishers.noBody());
    }

    private void assertRefreshRejected(String refreshToken) throws Exception {
        HttpResponse<String> response = send(refreshRequest(refreshToken), 401);
        assertErrorCode(response, "AUTH_0006");
        assertCookieExpired(response);
    }

    private void assertCookieExpired(HttpResponse<String> response) {
        HttpCookie cookie = refreshCookie(response);
        assertThat(cookie.getMaxAge()).isZero();
        assertThat(cookie.getValue()).isEmpty();
    }

    private void assertCredentialedCors(HttpResponse<String> response) {
        assertThat(response.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .contains(ORIGIN);
        assertThat(response.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .contains("true");
    }

    private TokenPair tokensFrom(HttpResponse<String> response) {
        JsonNode data = dataFrom(response);
        String accessToken = data.path("accessToken").asString();
        HttpCookie cookie = refreshCookie(response);
        assertThat(accessToken.isBlank()).isFalse();
        assertThat(data.has("refreshToken")).isFalse();
        assertThat(cookie.getValue().isBlank()).isFalse();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getMaxAge()).isPositive();
        return new TokenPair(accessToken, cookie.getValue());
    }

    private HttpCookie refreshCookie(HttpResponse<String> response) {
        HttpCookie cookie = HttpCookie.parse(response.headers().firstValue(HttpHeaders.SET_COOKIE)
                .orElseThrow(() -> new AssertionError("Refresh Token의 Set-Cookie 헤더가 없습니다.")))
                .getFirst();
        assertThat(cookie.getName()).isEqualTo("refresh_token");
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
        return cookie;
    }

    private void assertErrorCode(HttpResponse<String> response, String expected) {
        assertThat(objectMapper.readTree(response.body()).path("errorCode").asString())
                .isEqualTo(expected);
    }

    private JsonNode dataFrom(HttpResponse<String> response) {
        return objectMapper.readTree(response.body()).path("data");
    }

    private HttpRequest.Builder bearerRequest(String path, String accessToken) {
        return request(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(10));
    }

    private HttpRequest.Builder jsonRequest(
            HttpRequest.Builder request, String method, Map<String, String> body
    ) {
        return request.header(HttpHeaders.CONTENT_TYPE, "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
    }

    private HttpResponse<String> send(HttpRequest.Builder request, int expectedStatus) throws Exception {
        HttpRequest builtRequest = request.build();
        HttpResponse<String> response = client.send(builtRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("%s %s", builtRequest.method(), builtRequest.uri().getPath())
                .isEqualTo(expectedStatus);
        return response;
    }

    private record TokenPair(String accessToken, String refreshToken) {}
}

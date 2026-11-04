# AiProfessor 인증 및 토큰 정책

- 문서 버전: v0.1
- 작성일: 2026-11-04
- 상태: 확정
- 관련 요구사항: `AUTH-001`, `AUTH-002`, `AUTH-003`, `AUTH-004`, `AUTH-005`
- 관련 문서: `mvp-requirements.md`, `api-specification.md`, `api-specification-notion.md`, `table-specification.md`

> 이 문서는 AiProfessor MVP의 회원가입, 로그인, JWT, 인증 갱신, 로그아웃과 사용자 계정 보안 정책의 기준 문서다. 정책을 변경하면 API 명세, 테이블 명세, 테스트와 구현을 함께 변경한다.

## 1. 목적과 적용 범위

이 정책은 다음 기능에 적용한다.

- 이메일과 비밀번호를 이용한 회원가입
- Access Token과 Refresh Token을 이용한 로그인
- 보호 API의 사용자 인증과 권한 확인
- Refresh Token Rotation과 재사용 탐지
- 현재 세션 로그아웃
- 내 정보 조회·수정과 비밀번호 변경
- 계정 잠금·탈퇴 사용자의 접근 차단

다음 기능은 MVP 인증 범위에 포함하지 않는다.

- 소셜 로그인
- 이메일 소유 인증
- 비밀번호 찾기와 이메일 재설정
- 다중 인증(MFA)
- 기기 목록 조회와 원격 세션 종료
- 관리자에 의한 계정 잠금·해제 API

## 2. 핵심 정책 요약

| 항목 | MVP 기준 |
|---|---|
| 인증 방식 | 이메일·비밀번호 로그인 후 Bearer Access Token 사용 |
| Access Token | RS256 JWT, 유효기간 30분 |
| Refresh Token | 256bit 이상의 불투명 난수 토큰, 유효기간 14일 |
| Access Token 전달 | 로그인·갱신 성공 응답 본문 |
| Refresh Token 전달 | `HttpOnly` Cookie |
| Refresh Token 저장 | 원문을 저장하지 않고 SHA-256 해시만 저장 |
| 갱신 방식 | 사용할 때마다 Refresh Token Rotation |
| 재사용 탐지 | 이미 교체·폐기된 토큰 사용 시 해당 Token Family 전체 폐기 |
| 동시 로그인 | 허용하며, 로그인마다 독립된 Token Family 생성 |
| 로그아웃 범위 | 현재 세션의 Refresh Token 폐기 |
| 서버 세션 | 생성하지 않으며 Spring Security는 `STATELESS` 사용 |
| 사용자 식별자 | JWT `sub` Claim에 사용자 UUID 저장 |
| 시간 기준 | 모든 발급·만료·폐기 시각은 UTC 사용 |

## 3. 이메일 정책

### 3.1 입력과 정규화

- 이메일은 필수다.
- 앞뒤 공백을 제거한다.
- `Locale.ROOT` 기준으로 전체를 소문자로 변환한다.
- 이메일 형식을 검증한다.
- 최대 길이는 320자다.
- 정규화된 이메일을 저장하고 비교한다.

### 3.2 중복 처리

- `users.email`에 데이터베이스 유니크 제약조건을 둔다.
- 사전 중복 조회는 사용자 경험 개선을 위해 사용한다.
- 동시 가입 요청은 유니크 제약조건으로 최종 차단한다.
- 유니크 제약조건 위반은 `409 USER_0001`로 변환한다.

### 3.3 계정 존재 여부 보호

- 로그인 실패 시 이메일이 없는 경우와 비밀번호가 틀린 경우를 구분하지 않는다.
- 두 경우 모두 `401 AUTH_0001`과 동일한 사용자 메시지를 반환한다.
- 로그와 메트릭에도 이메일 존재 여부를 직접 기록하지 않는다.

## 4. 비밀번호 정책

### 4.1 입력 규칙

- 최소 8자, 최대 20자로 제한한다.
- 영문 대문자, 숫자, 특수문자 조합을 강제로 요구하지 않는다.
- 앞뒤 공백을 자동으로 제거하지 않는다. 공백도 사용자가 입력한 비밀번호의 일부로 취급한다.
- BCrypt의 입력 제한을 넘는 UTF-8 바이트 길이는 가입·변경 단계에서 거부한다.
- 비밀번호를 요청·응답·애플리케이션 로그·예외 메시지에 남기지 않는다.

### 4.2 저장과 검증

- Spring Security `DelegatingPasswordEncoder`를 사용한다.
- MVP 기본 인코더는 BCrypt를 사용한다.
- BCrypt strength 초기값은 12로 설정하고 배포 환경에서 로그인 지연 시간을 측정해 조정한다.
- 비밀번호 원문과 복호화 가능한 암호문을 저장하지 않는다.
- 비밀번호 일치 여부는 `PasswordEncoder.matches`로 확인한다.

### 4.3 비밀번호 변경

- 현재 비밀번호를 다시 검증한다.
- 현재 비밀번호와 같은 새 비밀번호는 거부한다.
- 변경 성공 시 해당 사용자의 모든 활성 Refresh Token을 폐기한다.
- 이미 발급된 Access Token은 별도 차단 목록을 두지 않으므로 최대 30분까지 유효할 수 있다.
- 비밀번호 변경 후 클라이언트는 Access Token과 사용자 상태를 제거하고 다시 로그인한다.

> 비밀번호 변경 직후 Access Token까지 즉시 무효화해야 한다면 `token_version` 또는 Access Token 차단 목록을 별도 설계한다. MVP에서는 구현 복잡도와 저장소 비용을 줄이기 위해 적용하지 않는다.

## 5. 사용자 계정 상태

| 상태 | 로그인 | 토큰 갱신 | 보호 API | 설명 |
|---|---:|---:|---:|---|
| `ACTIVE` | 허용 | 허용 | 허용 | 정상 사용자 |
| `LOCKED` | 거부 | 거부 | 거부 | 보안 또는 운영상 잠긴 계정 |
| `WITHDRAWN` | 거부 | 거부 | 거부 | 탈퇴 접수 또는 완료된 계정 |

- 로그인에서는 비밀번호가 일치한 뒤 계정 상태를 확인한다.
- 잠긴 사용자는 `401 AUTH_0007`을 반환한다.
- 탈퇴 사용자는 인증과 데이터 접근을 즉시 차단한다.
- 토큰 갱신 시에도 현재 사용자 상태가 `ACTIVE`인지 확인한다.
- 계정이 `LOCKED` 또는 `WITHDRAWN`으로 바뀌면 모든 활성 Refresh Token을 폐기한다.

Access Token은 자체 검증에 성공하더라도 보호 API에서 현재 사용자 상태를 확인해야 한다. 
이 조회 비용이 문제가 되면 사용자 상태 캐시 또는 `token_version` 정책을 후속 ADR로 검토한다.

## 6. Access Token 정책

### 6.1 형식과 서명

- Access Token은 RS256으로 서명한 JWT다.
- RSA 키는 최소 2048bit를 사용한다.
- 개인 키는 토큰 발급에만 사용하고 공개 키는 검증에 사용한다.
- 개인 키를 Git 저장소, 이미지, 로그에 포함하지 않는다.
- 로컬 환경은 Git에서 제외된 개발 키를 사용하고 운영 환경은 환경변수 또는 AWS Secrets Manager에서 주입한다.
- 키 회전을 위해 JWT Header에 `kid`를 포함할 수 있도록 구조를 준비한다.

### 6.2 Claim

| Claim | 값 | 목적 |
|---|---|---|
| `iss` | `aiprofessor` | 발급자 확인 |
| `aud` | `aiprofessor-api` | 대상 API 확인 |
| `sub` | 사용자 UUID 문자열 | 인증 사용자 식별 |
| `role` | `USER` 또는 `ADMIN` | 역할 권한 변환 |
| `jti` | 토큰별 UUID | 토큰 추적 식별자 |
| `typ` | `access` | Refresh Token 등 다른 토큰과 구분 |
| `iat` | 발급 시각 | 토큰 발급 시점 확인 |
| `exp` | 발급 시각 + 30분 | 만료 확인 |

- 이메일과 표시 이름은 변경될 수 있으므로 JWT에 저장하지 않는다.
- 비밀번호, Refresh Token, 내부 저장 키와 민감정보를 Claim에 포함하지 않는다.
- 서버 시계 오차 허용 범위는 최대 60초로 제한한다.

### 6.3 전달과 클라이언트 저장

- Access Token은 로그인과 갱신 성공 응답 본문으로 전달한다.
- 보호 API에는 `Authorization: Bearer {accessToken}` 헤더로 전달한다.
- React 클라이언트에서는 Access Token을 메모리에 저장한다.
- `localStorage`와 `sessionStorage`에는 Access Token을 저장하지 않는다.
- 페이지 새로고침 후에는 Refresh Token Cookie로 갱신 API를 호출해 Access Token을 다시 발급받는다.

### 6.4 검증

보호 API는 최소한 다음 항목을 검증한다.

- 서명
- `iss`
- `aud`
- `typ=access`
- `exp`, `iat`
- `sub`가 유효한 UUID인지 여부
- `role`이 허용된 값인지 여부
- 사용자의 현재 계정 상태

## 7. Refresh Token 정책

### 7.1 생성과 저장

- CSPRNG로 최소 256bit 난수를 생성한다.
- 외부 표현은 `rft_` 접두사와 Base64 URL-safe 문자열을 사용한다.
- Refresh Token은 JWT로 만들지 않는다.
- 서버는 토큰 원문을 발급 순간에만 사용하고 DB에는 SHA-256 해시를 저장한다.
- 난수 공간이 충분히 크므로 조회 가능한 결정적 해시를 사용하며 비밀번호용 BCrypt는 사용하지 않는다.
- `token_hash`에는 유니크 제약조건을 둔다.

### 7.2 Cookie

| 속성 | 운영 환경 기준 |
|---|---|
| 이름 | `refresh_token` |
| `HttpOnly` | `true` |
| `Secure` | `true` |
| `SameSite` | `Lax` |
| `Path` | `/api/v1/auth` |
| `Max-Age` | 14일 |
| `Domain` | 설정하지 않는 Host-only Cookie |

- 로컬 HTTP 개발 환경에서만 `Secure=false` 프로필을 허용한다.
- 운영 환경에서 프론트엔드와 API는 가능한 한 같은 사이트에 배치한다.
- CORS는 허용 Origin을 명시하고 `allowCredentials=true`를 사용한다.
- `*` Origin과 Credential 요청을 함께 허용하지 않는다.

### 7.3 Token Family와 동시 로그인

- 로그인 성공마다 새로운 `token_family_id`를 생성한다.
- 한 번의 로그인에서 회전된 Refresh Token은 같은 Token Family를 유지한다.
- 여러 브라우저나 기기의 동시 로그인을 허용한다.
- 각 로그인 세션은 서로 다른 Token Family를 가지므로 한 세션의 일반 로그아웃이 다른 세션을 종료하지 않는다.
- 비밀번호 변경, 계정 잠금과 회원 탈퇴는 사용자의 모든 활성 Token Family를 폐기한다.

### 7.4 클라이언트 정보

- `user_agent`는 최대 500자로 제한해 저장할 수 있다.
- IP 원문은 저장하지 않는다.
- IP 식별이 필요하면 서버 비밀값을 이용한 HMAC-SHA-256 결과만 `created_ip_hash`에 저장한다.
- User-Agent와 IP 해시는 보안 분석 보조 정보이며 인증 근거로 사용하지 않는다.

## 8. 로그인 정책

`POST /api/v1/auth/login`

처리 순서는 다음과 같다.

1. 이메일을 정규화한다.
2. 사용자를 조회한다.
3. 사용자가 없더라도 비밀번호 검증과 유사한 비용이 들도록 처리해 시간 차이를 줄인다.
4. 비밀번호를 검증한다.
5. 비밀번호가 일치하면 계정 상태를 확인한다.
6. Access Token과 Refresh Token을 생성한다.
7. Refresh Token 해시와 Token Family를 저장한다.
8. `last_login_at`을 갱신한다.
9. Access Token은 응답 본문으로, Refresh Token은 Cookie로 반환한다.

- 이메일이 없거나 비밀번호가 틀리면 모두 `401 AUTH_0001`을 반환한다.
- 비밀번호 실패 횟수 기반 자동 잠금은 MVP 범위에서 제외한다.
- 자동화 공격 대응 Rate Limit은 AWS 배포 및 운영 보안 단계에서 추가한다.

## 9. 토큰 갱신과 Rotation

`POST /api/v1/auth/refresh`

- 요청 Body로 Refresh Token을 받지 않는다.
- 브라우저가 전송한 `refresh_token` Cookie를 사용한다.
- 성공 응답 Body에는 새 Access Token과 만료 시간만 포함한다.
- 새 Refresh Token은 회전된 Cookie로 반환한다.

처리 순서는 다음과 같다.

1. Cookie의 Refresh Token을 SHA-256으로 해시한다.
2. `token_hash`로 레코드를 조회하면서 행 잠금을 획득한다.
3. 토큰 존재 여부, 만료, 폐기, 교체 여부를 확인한다.
4. 사용자 계정이 `ACTIVE`인지 확인한다.
5. 같은 Token Family로 새 Refresh Token 레코드를 생성한다.
6. 기존 토큰의 `revoked_at`과 `replaced_by_token_id`를 기록한다.
7. 새 Access Token과 Refresh Token을 발급한다.
8. 위 변경을 하나의 데이터베이스 트랜잭션으로 커밋한다.

### 9.1 동시 갱신

- 같은 Refresh Token으로 동시에 두 요청이 들어와도 하나만 성공해야 한다.
- 비관적 잠금 또는 동등한 원자적 갱신으로 중복 성공을 막는다.
- 먼저 처리된 요청이 기존 토큰을 교체하고, 뒤 요청은 재사용으로 판단한다.

### 9.2 재사용 탐지

- `revoked_at` 또는 `replaced_by_token_id`가 설정된 토큰은 이미 사용된 토큰이다.
- 이미 사용된 토큰이 다시 제시되면 해당 `token_family_id`의 활성 토큰을 모두 폐기한다.
- `401 AUTH_0006`을 반환하고 사용자가 다시 로그인하도록 한다.
- 다른 로그인에서 생성된 Token Family는 폐기하지 않는다.

## 10. 로그아웃 정책

`POST /api/v1/auth/logout`

- 유효한 Access Token 인증이 필요하다.
- 현재 요청의 Refresh Token Cookie를 확인한다.
- Refresh Token의 사용자가 Access Token의 `sub`와 같은지 검증한다.
- 현재 Refresh Token만 폐기하고 Cookie를 즉시 만료시킨다.
- 같은 로그아웃 요청을 반복해도 `204 No Content`를 반환한다.
- 존재하지 않거나 이미 폐기된 Cookie로 로그아웃해도 토큰 존재 여부를 노출하지 않는다.

Access Token은 서버에 저장하지 않으므로 로그아웃 후에도 최대 30분 동안 암호학적으로 유효하다. 클라이언트는 로그아웃 성공 즉시 메모리의 Access Token과 사용자 정보를 제거한다.

## 11. 내 정보와 사용자 식별

- 보호 API는 요청 Body, Query 또는 Path에서 전달된 사용자 ID를 인증 사용자 ID로 신뢰하지 않는다.
- 사용자 식별은 검증된 Access Token의 `sub`를 사용한다.
- `GET /api/v1/users/me`는 인증 사용자의 정보만 반환한다.
- `PATCH /api/v1/users/me`는 인증 사용자의 표시 이름만 변경한다.
- API 응답에 `password_hash`, `token_hash`, 내부 키와 보안 설정을 포함하지 않는다.
- 문서·요약·퀴즈 등 사용자 소유 리소스는 인증 사용자 ID를 조회 조건에 반드시 포함한다.
- 타인의 리소스에는 `403`이 아니라 `404`를 반환해 존재 여부를 숨긴다.

## 12. 회원 탈퇴 정책

`DELETE /api/v1/users/me`

- 현재 비밀번호를 다시 확인한다.
- 계정 상태를 `WITHDRAWN`으로 변경하고 `withdrawn_at`을 기록한다.
- 모든 활성 Refresh Token을 즉시 폐기한다.
- 탈퇴 이후 로그인, 토큰 갱신과 보호 API 접근을 차단한다.
- PDF 원본과 파생 데이터 삭제는 비동기 작업으로 처리한다.
- 비동기 삭제와 재시도는 Outbox·SQS 기반 삭제 흐름이 준비된 뒤 구현한다.

회원 탈퇴 최종 구현은 `AUTH-005`의 P1 범위이며 초기 인증 MVP에서는 상태 변경과 토큰 폐기 정책까지만 설계한다.

## 13. Cookie 요청 보호

Refresh Token은 브라우저가 자동 전송하는 Cookie이므로 CSRF 관점의 보호가 필요하다.

- 운영 환경은 `SameSite=Lax`, `Secure`, Host-only Cookie를 사용한다.
- 로그인, 갱신, 로그아웃 요청의 `Origin`을 서버 허용 목록과 비교한다.
- 허용되지 않은 Origin의 Credential 요청을 거부한다.
- 상태 변경 요청은 `GET`으로 제공하지 않는다.
- Bearer Access Token만 사용하는 일반 API는 서버 세션 기반 CSRF 보호 대상이 아니다.

프론트엔드와 API를 서로 다른 Site에 배포해야 한다면 `SameSite=None; Secure`와 별도의 CSRF Token 정책을 설계하고 문서를 개정한다.

## 14. 에러 코드

외부 API 에러 코드는 `도메인_0000` 형식을 사용한다.

| 코드 | HTTP | 의미 |
|---|---:|---|
| `AUTH_0001` | 401 | 이메일 또는 비밀번호 불일치 |
| `AUTH_0002` | 401 | 유효하지 않은 Access Token |
| `AUTH_0003` | 401 | 만료된 Access Token |
| `AUTH_0004` | 401 | 유효하지 않은 Refresh Token |
| `AUTH_0005` | 401 | 만료된 Refresh Token |
| `AUTH_0006` | 401 | 교체·폐기된 Refresh Token 재사용 |
| `AUTH_0007` | 401 | 잠긴 계정 |
| `AUTH_0008` | 401 | 인증이 필요한 요청 |
| `USER_0001` | 409 | 이미 가입된 이메일 |
| `USER_0002` | 401 | 탈퇴한 계정 |
| `USER_0003` | 400 | 현재 비밀번호 불일치 |
| `USER_0004` | 400 | 기존 비밀번호와 같은 새 비밀번호 |
| `COMMON_0001` | 400 | 요청 값 검증 실패 |

- 사용자 메시지는 안전하고 일관된 문구를 사용한다.
- 내부 예외 메시지, SQL, Stack Trace와 토큰 검증 상세 사유를 응답하지 않는다.
- 상세 의미 레이블을 내부 코드에서 사용하더라도 외부 응답은 위 코드를 사용한다.

## 15. 로그와 민감정보

다음 값을 애플리케이션 로그, 감사 로그, 추적 시스템과 오류 응답에 기록하지 않는다.

- 비밀번호와 비밀번호 해시
- Access Token과 Refresh Token
- JWT 개인 키
- Cookie 전체 값
- Authorization Header 전체 값

운영 추적에는 다음과 같은 비민감 정보만 사용한다.

- Correlation ID
- 사용자 UUID
- Refresh Token 레코드 UUID 또는 Token Family UUID
- JWT `jti`
- 성공·실패 결과와 안전한 실패 분류
- 요청 시각, 지연 시간과 클라이언트 유형

이메일을 로그에 남겨야 하는 운영 요구가 생기면 마스킹 또는 HMAC 기반 식별자를 사용한다.

## 16. 필수 테스트

### 16.1 회원가입

- 정상 가입
- 이메일 정규화
- 비밀번호 해시 저장
- 중복 이메일과 동시 가입 경쟁 조건
- 입력값 검증

### 16.2 로그인과 Access Token

- 정상 로그인과 Claim 검증
- 없는 이메일과 잘못된 비밀번호의 동일 오류
- 잠김·탈퇴 계정 차단
- 만료·변조·잘못된 issuer·audience·type 거부
- 인증되지 않은 보호 API의 JSON `401`

### 16.3 Refresh Token

- 토큰 원문 미저장
- 정상 Rotation
- 기존 토큰 폐기와 새 토큰 연결
- 만료 토큰 거부
- 동시 갱신 시 하나의 요청만 성공
- 재사용 시 같은 Token Family 전체 폐기
- 다른 로그인 세션의 Token Family 유지

### 16.4 로그아웃과 사용자 정보

- 현재 세션 로그아웃
- 로그아웃한 Refresh Token 갱신 거부
- 중복 로그아웃의 멱등성
- Refresh Token 소유권 확인
- 내 정보만 조회·수정
- 민감한 필드 응답 제외

Persistence 통합 테스트는 PostgreSQL Testcontainers를 사용한다. 인증 전체 변경은 Spring Modulith 검증과 `./gradlew clean build`를 통과해야 한다.

## 17. 구현 완료 기준

- 회원가입, 로그인, 갱신, 로그아웃과 내 정보 API가 정책대로 동작한다.
- Access Token과 Refresh Token의 생성·전달·저장 방식이 문서와 일치한다.
- Refresh Token Rotation, 재사용 탐지와 동시 갱신 제어가 테스트된다.
- 비밀번호, Token, Key가 코드와 로그에 노출되지 않는다.
- 사용자 계정 상태와 리소스 소유권이 모든 보호 API에 적용된다.
- API 명세, 테이블 명세와 OpenAPI가 실제 구현과 일치한다.
- Flyway 마이그레이션을 빈 PostgreSQL 데이터베이스에 적용할 수 있다.
- 필수 자동화 테스트와 CI가 통과한다.

## 18. 연계 문서 관리 규칙

이 정책의 인증 계약은 API 명세와 테이블 명세에 동일하게 반영한다. 정책을 변경할 때 다음 항목도 함께 변경한다.

### API 명세

- 로그인 응답에서 Refresh Token 본문 필드를 제거하고 `Set-Cookie`를 명시한다.
- 갱신 요청 Body의 Refresh Token을 제거하고 Cookie 입력으로 변경한다.
- 갱신 응답에서 Refresh Token 본문 필드를 제거하고 회전된 `Set-Cookie`를 명시한다.
- 로그아웃 요청 Body의 Refresh Token을 제거하고 Cookie 입력으로 변경한다.
- Access Token·Refresh Token 만료 시간을 명시한다.
- 영문 의미 레이블을 `AUTH_0000`, `USER_0000` 외부 코드와 연결한다.

### 테이블 명세

- `refresh_tokens`가 SHA-256 해시를 저장한다는 점을 명시한다.
- Token Family 동시 갱신을 위한 조회·잠금 쿼리와 인덱스를 검토한다.
- 계정 상태의 즉시 검증 비용을 개선할 경우 `token_version` 추가 여부를 검토한다.

### 운영 설정

- 개발·테스트·운영별 RSA 키 주입 방법을 정의한다.
- 환경별 Cookie `Secure`와 허용 Origin을 설정한다.
- 운영 단계에서 로그인·갱신 API Rate Limit을 정의한다.

## 19. 후속 고도화 검토 항목

다음 항목은 현재 인증 구현을 막지 않으며, 운영 요구와 측정 결과에 따라 별도 이슈 또는 ADR로 검토한다.

1. 실제 사용성과 보안 요구에 따른 Access Token 30분·Refresh Token 14일 조정
2. 단일 서버 구조 변경 시 RS256 키 관리와 회전 방식 고도화
3. 사용자별 동시 로그인 기기 수 제한과 세션 관리 화면
4. 전체 세션 로그아웃 API
5. 비밀번호 변경 직후 기존 Access Token을 즉시 차단하는 `token_version` 또는 차단 목록
6. Cross-Site 배포 시 `SameSite=None`과 CSRF Token 정책
7. 로그인 실패 기반 계정 잠금과 Rate Limit 도입 시점

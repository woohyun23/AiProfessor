# API 명세서

## 💡 Prefix

- 외부 API: `/api/v1`
- 내부 API: `/internal/v1`
- Resource는 복수형을 사용한다.
- URL에는 동사보다 Resource와 상태를 사용한다.
- JSON 필드는 camelCase를 사용한다.
- 시간은 ISO 8601 UTC 형식으로 전달한다.
- 인증이 필요한 API는 `Authorization: Bearer {accessToken}`을 사용한다.
- 비동기 작업 접수 API는 `202 Accepted`와 `jobId`를 반환한다.

## 공통 응답 포맷

```json
// 응답 포맷(성공 - 단건)
{
  "success": true,
  "message": "조회 성공",
  "data": {
    "id": "a21f6ec1-0d91-45fb-b5aa-4ee138cb48e7",
    "createdAt": "2026-11-02T10:30:00Z",
    "updatedAt": "2026-11-02T10:30:00Z"
  }
}

// 응답 포맷(성공 - 목록)
{
  "success": true,
  "message": "목록 조회 성공",
  "data": [
    {
      "id": "a21f6ec1-0d91-45fb-b5aa-4ee138cb48e7",
      "createdAt": "2026-11-02T10:30:00Z",
      "updatedAt": "2026-11-02T10:30:00Z"
    }
  ],
  "page": {
    "nextCursor": null,
    "hasNext": false,
    "size": 20
  }
}

// 응답 포맷(실패)
{
  "success": false,
  "errorCode": "DOCUMENT_0003",
  "message": "문서 처리가 아직 완료되지 않았습니다.",
  "errors": [
    {
      "field": "documentId",
      "message": "READY 상태의 문서만 사용할 수 있습니다."
    }
  ]
}
```

## 에러코드 규칙

```text
도메인_0000

예시
AUTH_0001
DOCUMENT_0003
AI_0002
```

| 접두사 | 도메인 | 예시 |
|---|---|---|
| `COMMON` | 공통·검증 | `COMMON_0001` 요청값 검증 실패 |
| `AUTH` | 인증·토큰 | `AUTH_0001` 인증 정보 불일치 |
| `USER` | 사용자 | `USER_0001` 이메일 중복 |
| `DOCUMENT` | PDF 문서 | `DOCUMENT_0003` 문서 처리 미완료 |
| `JOB` | 비동기 작업 | `JOB_0002` 재시도 불가능 |
| `SUMMARY` | 요약 | `SUMMARY_0001` 요약 없음 |
| `QUIZ` | 문제 생성 | `QUIZ_0002` 문제 개수 오류 |
| `ATTEMPT` | 답안·채점 | `ATTEMPT_0003` 답안 유형 불일치 |
| `REVIEW` | 오답·재학습 | `REVIEW_0001` 오답 항목 없음 |
| `AI` | LLM·임베딩 | `AI_0001` 외부 AI 시간 초과 |

## HTTP 상태 코드

| 상태 코드 | 사용 기준 |
|---:|---|
| `200 OK` | 조회·수정 성공 |
| `201 Created` | 동기 리소스 생성 성공 |
| `202 Accepted` | 비동기 작업 접수 성공 |
| `204 No Content` | 응답 본문 없는 로그아웃·삭제 성공 |
| `400 Bad Request` | 요청 형식·유효성 오류 |
| `401 Unauthorized` | 인증 정보 없음·만료·유효하지 않음 |
| `403 Forbidden` | 역할 권한 부족 |
| `404 Not Found` | 리소스 없음 또는 소유권 없음 |
| `409 Conflict` | 중복 또는 현재 상태 충돌 |
| `413 Payload Too Large` | 파일 크기 제한 초과 |
| `415 Unsupported Media Type` | PDF가 아닌 파일 |
| `422 Unprocessable Entity` | 현재 상태에서 처리할 수 없는 요청 |
| `429 Too Many Requests` | 요청·AI 사용량 제한 초과 |
| `500 Internal Server Error` | 예상하지 못한 서버 오류 |
| `502 Bad Gateway` | 외부 AI·저장소 오류 |
| `503 Service Unavailable` | 일시적인 서비스 사용 불가 |

## API 목록

> 담당자 이름은 현재 프로젝트 소유자인 `박우현`으로 기입했다. 팀원이 합류하면 Notion 데이터베이스에서 담당자만 변경한다.

| 담당자 | 도메인 | 기능 | Method | url | Param | 접근 권한 | 권한 여부 | 설명 | 비고 |
|---|---|---|---|---|---|---|---|---|---|
| 박우현 | User | 회원가입 | POST | `/api/v1/users` | Body | 비회원 | N | 이메일, 비밀번호, 이름을 입력받아 신규 사용자 계정을 생성한다. | 이메일 중복 검증 |
| 박우현 | Auth | 로그인 | POST | `/api/v1/auth/login` | Body | 비회원 | N | 이메일과 비밀번호를 검증하고 Access Token과 Refresh Token을 발급한다. | 토큰 전달 방식 추후 확정 |
| 박우현 | Auth | 토큰 재발급 | POST | `/api/v1/auth/refresh` | Body | 비회원 | N | Refresh Token을 검증·회전하고 새 토큰을 발급한다. | 재사용 탐지 시 토큰 패밀리 폐기 |
| 박우현 | Auth | 로그아웃 | POST | `/api/v1/auth/logout` | Body | 일반 이용자 | Y | 현재 Refresh Token을 폐기한다. | Access Token은 만료까지 유효 |
| 박우현 | User | 내 정보 조회 | GET | `/api/v1/users/me` | - | 일반 이용자 | Y | 로그인한 사용자의 계정 정보를 조회한다. |  |
| 박우현 | User | 내 정보 수정 | PATCH | `/api/v1/users/me` | Body | 일반 이용자 | Y | 로그인한 사용자의 표시 이름을 변경한다. |  |
| 박우현 | User | 회원 탈퇴 | DELETE | `/api/v1/users/me` | Body | 일반 이용자 | Y | 계정을 탈퇴 처리하고 모든 문서와 파생 데이터 삭제 작업을 접수한다. | 비동기 삭제, `202` |
| 박우현 | Document | PDF 업로드 | POST | `/api/v1/documents` | Multipart | 일반 이용자 | Y | PDF를 검증·저장하고 문서 처리 작업을 비동기로 접수한다. | 최대 50MB·300페이지, `202` |
| 박우현 | Document | 문서 목록 조회 | GET | `/api/v1/documents` | Query | 일반 이용자 | Y | 사용자가 업로드한 문서 목록을 상태 조건과 Cursor로 조회한다. | 본인 문서만 조회 |
| 박우현 | Document | 문서 상세 조회 | GET | `/api/v1/documents/{documentId}` | Path | 일반 이용자 | Y | 파일 정보, 페이지 수, 처리 상태와 최신 작업을 조회한다. | 타인 문서는 `404` |
| 박우현 | Document | PDF 열람 URL 발급 | POST | `/api/v1/documents/{documentId}/access-url` | Path, Body | 일반 이용자 | Y | PDF 근거 뷰어에서 사용할 단기 서명 URL을 발급한다. | 만료 시간이 짧은 URL 사용 |
| 박우현 | Document | 문서 삭제 | DELETE | `/api/v1/documents/{documentId}` | Path | 일반 이용자 | Y | 문서 접근을 즉시 차단하고 원본·청크·벡터·학습 데이터 삭제를 접수한다. | 비동기 삭제, 멱등 키 필수 |
| 박우현 | Job | 문서 최신 작업 조회 | GET | `/api/v1/documents/{documentId}/jobs/latest` | Path, Query | 일반 이용자 | Y | 문서의 최신 비동기 작업 상태와 진행률을 조회한다. | 클라이언트 폴링 |
| 박우현 | Job | 작업 상세 조회 | GET | `/api/v1/jobs/{jobId}` | Path | 일반 이용자 | Y | 특정 작업의 단계, 재시도 횟수와 실패 사유를 조회한다. |  |
| 박우현 | Job | 실패 작업 재시도 | POST | `/api/v1/jobs/{jobId}/retry` | Path | 일반 이용자 | Y | 재시도 가능한 실패 작업을 새 작업으로 다시 접수한다. | `FAILED` 상태만 가능 |
| 박우현 | Summary | 요약 생성 | POST | `/api/v1/documents/{documentId}/summaries` | Path, Body | 일반 이용자 | Y | 문서 전체 또는 특정 주제 요약을 비동기로 생성한다. | READY 문서만 가능, `202` |
| 박우현 | Summary | 문서 요약 목록 조회 | GET | `/api/v1/documents/{documentId}/summaries` | Path, Query | 일반 이용자 | Y | 문서에서 생성한 요약 목록을 조회한다. | 전체·주제 유형 필터 |
| 박우현 | Summary | 요약 상세 조회 | GET | `/api/v1/summaries/{summaryId}` | Path | 일반 이용자 | Y | 요약 본문과 PDF 페이지 근거를 조회한다. | 근거 부족 여부 포함 |
| 박우현 | Summary | 요약 삭제 | DELETE | `/api/v1/summaries/{summaryId}` | Path | 일반 이용자 | Y | 특정 요약과 연결된 인용 정보를 삭제한다. |  |
| 박우현 | Quiz | 문제 묶음 생성 | POST | `/api/v1/documents/{documentId}/quiz-sets` | Path, Body | 일반 이용자 | Y | 유형·개수·난이도·주제를 입력받아 문제 묶음을 비동기로 생성한다. | 합계 1~20문제, `202` |
| 박우현 | Quiz | 문제 묶음 목록 조회 | GET | `/api/v1/documents/{documentId}/quiz-sets` | Path, Query | 일반 이용자 | Y | 문서에서 생성한 문제 묶음 목록을 조회한다. | 상태 필터·Cursor |
| 박우현 | Quiz | 문제 묶음 상세 조회 | GET | `/api/v1/quiz-sets/{quizSetId}` | Path | 일반 이용자 | Y | 문제 묶음과 개별 문제·선택지를 조회한다. | 제출 전 정답 미노출 |
| 박우현 | Quiz | 문제 단건 조회 | GET | `/api/v1/questions/{questionId}` | Path | 일반 이용자 | Y | 특정 문제와 선택지를 조회한다. | 제출 전 해설·채점 기준 미노출 |
| 박우현 | Attempt | 답안 제출 | POST | `/api/v1/questions/{questionId}/attempts` | Path, Body | 일반 이용자 | Y | 객관식 또는 주관식 답안을 제출하고 채점을 수행한다. | 객관식 `201`, 주관식 `202` |
| 박우현 | Attempt | 풀이 결과 조회 | GET | `/api/v1/attempts/{attemptId}` | Path | 일반 이용자 | Y | 정오답, 점수, 해설, 부족한 핵심어와 근거를 조회한다. | 주관식은 AI 평가 표시 |
| 박우현 | Attempt | 문제별 풀이 이력 조회 | GET | `/api/v1/questions/{questionId}/attempts` | Path, Query | 일반 이용자 | Y | 특정 문제의 시도별 답안과 채점 결과를 조회한다. | 재도전 이력 포함 |
| 박우현 | Review | 오답 목록 조회 | GET | `/api/v1/reviews` | Query | 일반 이용자 | Y | 오답·헷갈림 문제를 문서, 사유, 상태 조건으로 조회한다. | Cursor 페이지네이션 |
| 박우현 | Review | 오답 상태 수정 | PATCH | `/api/v1/reviews/{reviewItemId}` | Path, Body | 일반 이용자 | Y | 헷갈림 여부와 학습 상태를 변경한다. | OPEN·MASTERED·ARCHIVED |
| 박우현 | Review | 오답 재도전 | POST | `/api/v1/reviews/{reviewItemId}/attempts` | Path, Body | 일반 이용자 | Y | 오답 항목에 연결된 문제에 새 답안을 제출한다. | 정답 시 MASTERED 전환 |
| 박우현 | Operation | 서비스 상태 조회 | GET | `/actuator/health` | - | 내부 시스템 | Y | 애플리케이션의 기본 가용 상태를 조회한다. | 상세 상태는 외부 공개 금지 |

## Notion 데이터베이스 붙여넣기 방법

1. Notion의 API 데이터베이스에서 `담당자`부터 `비고`까지 열 순서를 맞춘다.
2. [API 목록 TSV](api-specification-notion.tsv) 파일을 열고 헤더를 제외한 전체 행을 복사한다.
3. Notion 데이터베이스 첫 번째 빈 행의 `담당자` 셀을 선택하고 붙여넣는다.
4. `Method`, `도메인`, `접근 권한` 속성 유형을 Select로 변경한다.
5. `권한 여부` 속성은 Checkbox로 변경하고 `Y`를 체크 상태로 변환한다.

## 작성 규칙

- 목록 조회 API는 Resource 복수형을 사용한다.
- 단건 조회·수정·삭제는 `/{resourceId}`를 사용한다.
- 동사형 URL은 로그인, 토큰 갱신, 재시도처럼 Resource 상태 전이로 표현하기 어려운 경우에만 사용한다.
- 비동기 생성 API는 생성될 결과 Resource ID와 작업 `jobId`를 함께 반환한다.
- `POST`, 비동기 재시도, 답안 제출, 삭제 요청에는 필요한 경우 `Idempotency-Key`를 적용한다.
- 사용자가 소유하지 않은 Resource는 존재 여부를 숨기기 위해 `404`를 반환한다.
- 객관식 정답, 주관식 기준 답안, 해설, 채점 기준은 답안 제출 전에 노출하지 않는다.


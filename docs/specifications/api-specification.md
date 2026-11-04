# AiProfessor MVP API 명세서

- 문서 버전: v0.1 (초안)
- 작성일: 2026-11-02
- 기준 문서: `AiProfessor MVP 요구사항 명세서 v0.1`, `테이블 명세서 v0.1`, `인증 및 토큰 정책 v0.1`
- Base URL: `/api/v1`
- 용도: Notion 붙여넣기 및 OpenAPI 작성 기준

> 공통 응답 포맷과 외부 에러코드 계약은 `api-specification-notion.md`를 정본으로 사용한다. 이 문서의 `DOCUMENT_NOT_READY` 같은 영문 상수는 상세 동작을 설명하기 위한 의미 레이블이며, 외부 응답에서는 `DOCUMENT_0003` 같은 `도메인_0000` 코드를 사용한다.

## 1. API 설계 원칙

| 항목 | 규칙 |
|---|---|
| 통신 형식 | HTTPS + JSON, 파일 업로드만 `multipart/form-data` |
| 인증 | `Authorization: Bearer {accessToken}` |
| 식별자 | UUID 문자열 |
| 날짜·시간 | ISO 8601 UTC, 예: `2026-11-02T10:30:00Z` |
| 필드명 | JSON은 camelCase, DB는 snake_case |
| 비동기 작업 | `202 Accepted`와 `jobId` 반환, 클라이언트가 상태 폴링 |
| 목록 조회 | Cursor 기반 페이지네이션 |
| 멱등성 | 생성·재시도·답안 제출 요청에 `Idempotency-Key` 사용 |
| 소유권 보호 | 타인 리소스는 존재 여부를 숨기기 위해 `404 Not Found` 반환 |
| 정답 보호 | 문제 제출 전 정답·정답 여부·채점 기준·해설 미노출 |

## 2. 공통 헤더

### 2.1 요청 헤더

| 헤더 | 필수 | 설명 |
|---|---:|---|
| `Authorization` | 보호 API 필수 | `Bearer {accessToken}` |
| `Content-Type` | 필수 | `application/json` 또는 `multipart/form-data` |
| `Idempotency-Key` | 일부 API 필수 | 클라이언트가 생성한 UUID 권장 |
| `X-Correlation-Id` | 선택 | 미입력 시 서버가 생성하고 응답에 반환 |

### 2.2 응답 헤더

| 헤더 | 설명 |
|---|---|
| `X-Correlation-Id` | 요청 추적 식별자 |
| `Location` | 생성된 리소스의 조회 경로, 적용 가능한 경우 반환 |

## 3. 공통 응답 형식

### 3.1 단건 성공 응답

```json
{
  "success": true,
  "message": "조회 성공",
  "data": {
    "id": "a21f6ec1-0d91-45fb-b5aa-4ee138cb48e7"
  }
}
```

### 3.2 목록 성공 응답

```json
{
  "success": true,
  "message": "목록 조회 성공",
  "data": [
    {
      "id": "a21f6ec1-0d91-45fb-b5aa-4ee138cb48e7"
    }
  ],
  "page": {
    "nextCursor": "eyJjcmVhdGVkQXQiOiIyMDI2LTExLTAyVDEwOjMwOjAwWiJ9",
    "hasNext": true,
    "size": 20
  }
}
```

### 3.3 오류 응답

```json
{
  "success": false,
  "errorCode": "DOCUMENT_0003",
  "message": "문서 처리가 아직 완료되지 않았습니다.",
  "errors": []
}
```

유효성 검증 실패 예시:

```json
{
  "success": false,
  "errorCode": "COMMON_0001",
  "message": "요청 값이 올바르지 않습니다.",
  "errors": [
    {
      "field": "password",
      "message": "비밀번호는 8자 이상이어야 합니다."
    }
  ]
}
```

## 4. 공통 HTTP 상태 코드

| 상태 | 사용 기준 |
|---:|---|
| `200 OK` | 조회·수정 성공 |
| `201 Created` | 동기 리소스 생성 성공 |
| `202 Accepted` | 비동기 작업 접수 성공 |
| `204 No Content` | 응답 본문 없는 로그아웃·삭제 성공 |
| `400 Bad Request` | 형식 오류, 유효성 검증 실패 |
| `401 Unauthorized` | 인증 정보 없음·만료·유효하지 않음 |
| `403 Forbidden` | 인증됐지만 역할 권한 부족 |
| `404 Not Found` | 리소스 없음 또는 소유권 없음 |
| `409 Conflict` | 이메일·파일·멱등 키 중복, 현재 상태 충돌 |
| `413 Payload Too Large` | 파일 크기 제한 초과 |
| `415 Unsupported Media Type` | PDF가 아닌 파일 |
| `422 Unprocessable Entity` | 형식은 맞지만 현재 상태에서 처리 불가 |
| `429 Too Many Requests` | 요청·AI 사용량 제한 초과 |
| `500 Internal Server Error` | 예상하지 못한 서버 오류 |
| `502 Bad Gateway` | 외부 AI·저장소 응답 오류 |
| `503 Service Unavailable` | 일시적인 서비스 사용 불가 |

## 5. 인증 API

## 5.1 회원가입

`POST /users`

인증: 불필요

요청:

```json
{
  "email": "learner@example.com",
  "password": "StrongPassword!23",
  "displayName": "학습자"
}
```

검증 규칙:

| 필드 | 규칙 |
|---|---|
| `email` | 필수, 이메일 형식, 최대 320자, 소문자 정규화 |
| `password` | 필수, 8~64자, BCrypt 입력 제한을 넘는 UTF-8 바이트 길이 거부 |
| `displayName` | 필수, 2~50자 |

성공: `201 Created`

```json
{
  "data": {
    "userId": "0a68688f-013d-4622-8cba-e3e7d31316df",
    "email": "learner@example.com",
    "displayName": "학습자",
    "createdAt": "2026-11-02T10:30:00Z"
  }
}
```

오류:

- `409 USER_0001` (`EMAIL_ALREADY_EXISTS`)
- `400 COMMON_0001` (`VALIDATION_FAILED`)

## 5.2 로그인

`POST /auth/login`

인증: 불필요

요청:

```json
{
  "email": "learner@example.com",
  "password": "StrongPassword!23"
}
```

성공: `200 OK`

응답 헤더:

```http
Set-Cookie: refresh_token=rft_...; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth; Max-Age=1209600
```

> 로컬 HTTP 개발 프로필에서만 `Secure`를 제외한다. Refresh Token 원문은 응답 본문에 포함하지 않는다.

```json
{
  "data": {
    "accessToken": "eyJ...",
    "tokenType": "Bearer",
    "expiresIn": 1800,
    "user": {
      "userId": "0a68688f-013d-4622-8cba-e3e7d31316df",
      "email": "learner@example.com",
      "displayName": "학습자",
      "role": "USER"
    }
  }
}
```

오류:

- `401 AUTH_0001` (`INVALID_CREDENTIALS`)
- `401 AUTH_0007` (`ACCOUNT_LOCKED`)

> 이메일 존재 여부가 추측되지 않도록 이메일 오류와 비밀번호 오류를 같은 코드로 응답한다.

## 5.3 Access Token 갱신

`POST /auth/refresh`

인증: Access Token 불필요, `refresh_token` Cookie 필요

요청:

```http
Cookie: refresh_token=rft_...
```

성공: `200 OK`

응답 헤더:

```http
Set-Cookie: refresh_token=rft_new...; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth; Max-Age=1209600
```

> 갱신할 때 Refresh Token Cookie를 회전하며 원문은 응답 본문에 포함하지 않는다.

```json
{
  "data": {
    "accessToken": "eyJ...new",
    "tokenType": "Bearer",
    "expiresIn": 1800
  }
}
```

정책:

- 갱신할 때 Refresh Token을 회전한다.
- 이미 사용·폐기된 토큰을 다시 사용하면 해당 토큰 패밀리를 모두 폐기한다.

오류:

- `401 AUTH_0004` (`INVALID_REFRESH_TOKEN`)
- `401 AUTH_0005` (`REFRESH_TOKEN_EXPIRED`)
- `401 AUTH_0006` (`REFRESH_TOKEN_REUSED`)

## 5.4 로그아웃

`POST /auth/logout`

인증: Access Token과 `refresh_token` Cookie 필요

요청:

```http
Authorization: Bearer eyJ...
Cookie: refresh_token=rft_...
```

성공: `204 No Content`

응답 헤더:

```http
Set-Cookie: refresh_token=; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth; Max-Age=0
```

> 로그아웃은 현재 Refresh Token을 폐기하고 Cookie를 만료시킨다. Access Token은 최대 30분의 자체 만료 시각까지 유효할 수 있다.

## 6. 문서 API

## 6.1 PDF 업로드

`POST /documents`

인증: 필요  
Content-Type: `multipart/form-data`  
`Idempotency-Key`: 필수

Form Data:

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `file` | Binary | Y | 텍스트 추출 가능한 PDF, 기본 최대 50MB·300페이지 |

성공: `202 Accepted`

```json
{
  "data": {
    "document": {
      "documentId": "b4d0b274-3f49-46de-a834-35cae401fb03",
      "fileName": "정보처리기사.pdf",
      "fileSizeBytes": 4839201,
      "status": "UPLOADED",
      "uploadedAt": "2026-11-02T10:30:00Z"
    },
    "job": {
      "jobId": "ba876cd4-0e58-4b66-b2b3-aee9ea641ac1",
      "jobType": "PROCESS_DOCUMENT",
      "status": "PENDING",
      "progressPercent": 0
    }
  }
}
```

오류:

- `409 DUPLICATE_DOCUMENT`
- `413 FILE_TOO_LARGE`
- `415 PDF_ONLY`
- `422 PDF_ENCRYPTED`
- `422 PDF_DAMAGED`
- `422 PDF_PAGE_LIMIT_EXCEEDED`
- `429 UPLOAD_LIMIT_EXCEEDED`

## 6.2 문서 목록 조회

`GET /documents?status={status}&cursor={cursor}&size={size}`

인증: 필요

쿼리:

| 파라미터 | 필수 | 기본값 | 설명 |
|---|---:|---|---|
| `status` | N | 전체 | 문서 상태 필터 |
| `cursor` | N | - | 다음 페이지 커서 |
| `size` | N | `20` | 1~100 |

성공: `200 OK`

```json
{
  "data": [
    {
      "documentId": "b4d0b274-3f49-46de-a834-35cae401fb03",
      "fileName": "정보처리기사.pdf",
      "fileSizeBytes": 4839201,
      "pageCount": 184,
      "status": "READY",
      "uploadedAt": "2026-11-02T10:30:00Z",
      "processedAt": "2026-11-02T10:35:18Z"
    }
  ],
  "page": {
    "nextCursor": null,
    "hasNext": false,
    "size": 20
  }
}
```

## 6.3 문서 상세 조회

`GET /documents/{documentId}`

인증: 필요

성공: `200 OK`

```json
{
  "data": {
    "documentId": "b4d0b274-3f49-46de-a834-35cae401fb03",
    "fileName": "정보처리기사.pdf",
    "fileSizeBytes": 4839201,
    "pageCount": 184,
    "status": "READY",
    "failure": null,
    "latestJob": {
      "jobId": "ba876cd4-0e58-4b66-b2b3-aee9ea641ac1",
      "status": "SUCCEEDED",
      "currentStep": "COMPLETED",
      "progressPercent": 100
    },
    "uploadedAt": "2026-11-02T10:30:00Z",
    "processedAt": "2026-11-02T10:35:18Z"
  }
}
```

오류:

- `404 DOCUMENT_NOT_FOUND`

## 6.4 PDF 열람 URL 발급

`POST /documents/{documentId}/access-url`

인증: 필요  
`Idempotency-Key`: 불필요

요청:

```json
{
  "page": 37
}
```

성공: `200 OK`

```json
{
  "data": {
    "url": "https://storage.example.com/signed/...",
    "expiresAt": "2026-11-02T10:40:00Z",
    "page": 37
  }
}
```

정책:

- URL은 짧은 시간만 유효한 서명 URL로 발급한다.
- 요청할 때마다 문서 소유권을 검증한다.
- 로컬 개발 환경에서는 인증된 스트리밍 엔드포인트로 대체할 수 있다.

## 6.5 문서 삭제

`DELETE /documents/{documentId}`

인증: 필요  
`Idempotency-Key`: 필수

성공: `202 Accepted`

```json
{
  "data": {
    "documentId": "b4d0b274-3f49-46de-a834-35cae401fb03",
    "status": "DELETING",
    "jobId": "6bf8ac26-dcf0-41bb-8dad-af370609718a"
  }
}
```

정책:

- 응답 직후 모든 사용자 API에서 문서 접근을 차단한다.
- 원본, 청크, 벡터, 요약, 문제, 풀이와 오답은 비동기로 삭제한다.

오류:

- `404 DOCUMENT_NOT_FOUND`
- `409 DOCUMENT_ALREADY_DELETING`

## 7. 비동기 작업 API

## 7.1 문서의 최신 작업 조회

`GET /documents/{documentId}/jobs/latest?jobType={jobType}`

인증: 필요

성공: `200 OK`

```json
{
  "data": {
    "jobId": "ba876cd4-0e58-4b66-b2b3-aee9ea641ac1",
    "jobType": "PROCESS_DOCUMENT",
    "targetType": "DOCUMENT",
    "targetId": "b4d0b274-3f49-46de-a834-35cae401fb03",
    "status": "RUNNING",
    "currentStep": "EMBEDDING",
    "progressPercent": 75,
    "retryCount": 1,
    "maxRetries": 3,
    "failure": null,
    "startedAt": "2026-11-02T10:30:03Z",
    "updatedAt": "2026-11-02T10:33:19Z"
  }
}
```

폴링 권장:

- 초기 2초 간격으로 조회한다.
- 장시간 처리 시 최대 5초까지 간격을 늘린다.
- `SUCCEEDED`, `FAILED`, `CANCELLED` 상태에서 폴링을 중지한다.

## 7.2 작업 단건 조회

`GET /jobs/{jobId}`

인증: 필요

성공: `200 OK`

응답 형식은 최신 작업 조회와 같다.

## 7.3 실패 작업 수동 재시도

`POST /jobs/{jobId}/retry`

인증: 필요  
`Idempotency-Key`: 필수

성공: `202 Accepted`

```json
{
  "data": {
    "originalJobId": "ba876cd4-0e58-4b66-b2b3-aee9ea641ac1",
    "jobId": "2acf6fe6-aaf2-4d64-9a4a-0fb96c5ddfad",
    "status": "PENDING",
    "retryOf": "ba876cd4-0e58-4b66-b2b3-aee9ea641ac1"
  }
}
```

오류:

- `409 JOB_NOT_FAILED`
- `409 JOB_NOT_RETRYABLE`
- `404 JOB_NOT_FOUND`

## 8. 요약 API

## 8.1 요약 생성 요청

`POST /documents/{documentId}/summaries`

인증: 필요  
`Idempotency-Key`: 필수

요청:

```json
{
  "summaryType": "TOPIC",
  "topic": "트랜잭션 격리 수준"
}
```

필드:

| 필드 | 필수 | 규칙 |
|---|---:|---|
| `summaryType` | Y | `FULL`, `TOPIC` |
| `topic` | 조건부 | `TOPIC`일 때 필수, 1~255자 |

성공: `202 Accepted`

```json
{
  "data": {
    "summaryId": "5cd40e35-b922-4987-90a4-e2e570a90158",
    "status": "PENDING",
    "jobId": "44e699a7-52ac-45e2-a080-972a608479c2"
  }
}
```

오류:

- `409 DOCUMENT_NOT_READY`
- `422 TOPIC_REQUIRED`
- `429 AI_USAGE_LIMIT_EXCEEDED`

## 8.2 문서 요약 목록 조회

`GET /documents/{documentId}/summaries?type={type}&cursor={cursor}&size={size}`

인증: 필요

성공: `200 OK`

```json
{
  "data": [
    {
      "summaryId": "5cd40e35-b922-4987-90a4-e2e570a90158",
      "summaryType": "TOPIC",
      "topic": "트랜잭션 격리 수준",
      "title": "트랜잭션 격리 수준 요약",
      "status": "READY",
      "insufficientEvidence": false,
      "createdAt": "2026-11-02T11:00:00Z"
    }
  ],
  "page": {
    "nextCursor": null,
    "hasNext": false,
    "size": 20
  }
}
```

## 8.3 요약 상세 조회

`GET /summaries/{summaryId}`

인증: 필요

성공: `200 OK`

```json
{
  "data": {
    "summaryId": "5cd40e35-b922-4987-90a4-e2e570a90158",
    "documentId": "b4d0b274-3f49-46de-a834-35cae401fb03",
    "summaryType": "TOPIC",
    "topic": "트랜잭션 격리 수준",
    "title": "트랜잭션 격리 수준 요약",
    "content": "트랜잭션 격리 수준은 ... [1]",
    "status": "READY",
    "insufficientEvidence": false,
    "citations": [
      {
        "citationId": "5726d3a6-fea6-4e66-8fb7-975e59e095c7",
        "order": 1,
        "pageStart": 37,
        "pageEnd": 38,
        "quote": "격리 수준은 동시에 실행되는 트랜잭션이 ..."
      }
    ],
    "createdAt": "2026-11-02T11:00:00Z"
  }
}
```

정책:

- 모델명과 프롬프트 버전은 기본 사용자 응답에서는 생략하고 운영·평가 API 또는 로그에서 조회한다.
- `insufficientEvidence=true`이면 근거 부족 안내를 제공하고 추측성 요약을 반환하지 않는다.

## 8.4 요약 삭제

`DELETE /summaries/{summaryId}`

인증: 필요

성공: `204 No Content`

## 9. 문제 생성 API

## 9.1 문제 묶음 생성 요청

`POST /documents/{documentId}/quiz-sets`

인증: 필요  
`Idempotency-Key`: 필수

요청:

```json
{
  "topic": "트랜잭션과 동시성",
  "difficulty": "MEDIUM",
  "mcqCount": 5,
  "saqCount": 2
}
```

검증 규칙:

| 필드 | 필수 | 규칙 |
|---|---:|---|
| `topic` | N | 미입력 시 문서 전체, 최대 255자 |
| `difficulty` | Y | `EASY`, `MEDIUM`, `HARD` |
| `mcqCount` | Y | 0~20 |
| `saqCount` | Y | 0~20 |
| 합계 | Y | 1~20 |

성공: `202 Accepted`

```json
{
  "data": {
    "quizSetId": "4a2c29c7-cd12-4f56-b401-759ec50df7c4",
    "status": "PENDING",
    "requestedQuestionCount": 7,
    "jobId": "671f3454-247c-4252-bcd1-4d471c4c1a80"
  }
}
```

오류:

- `409 DOCUMENT_NOT_READY`
- `400 INVALID_QUESTION_COUNT`
- `429 AI_USAGE_LIMIT_EXCEEDED`

## 9.2 문서의 문제 묶음 목록 조회

`GET /documents/{documentId}/quiz-sets?status={status}&cursor={cursor}&size={size}`

인증: 필요

성공: `200 OK`

```json
{
  "data": [
    {
      "quizSetId": "4a2c29c7-cd12-4f56-b401-759ec50df7c4",
      "topic": "트랜잭션과 동시성",
      "difficulty": "MEDIUM",
      "mcqCount": 5,
      "saqCount": 2,
      "status": "READY",
      "createdAt": "2026-11-02T11:20:00Z"
    }
  ],
  "page": {
    "nextCursor": null,
    "hasNext": false,
    "size": 20
  }
}
```

## 9.3 문제 묶음 상세 조회

`GET /quiz-sets/{quizSetId}`

인증: 필요

성공: `200 OK`

```json
{
  "data": {
    "quizSetId": "4a2c29c7-cd12-4f56-b401-759ec50df7c4",
    "documentId": "b4d0b274-3f49-46de-a834-35cae401fb03",
    "topic": "트랜잭션과 동시성",
    "difficulty": "MEDIUM",
    "status": "READY",
    "questions": [
      {
        "questionId": "f20f230b-4825-4550-8469-f7a016746168",
        "order": 1,
        "questionType": "MCQ",
        "questionText": "다음 중 격리 수준에 대한 설명으로 옳은 것은?",
        "choices": [
          {
            "choiceId": "068162b6-5028-4cd0-85d1-a98202fcc9f7",
            "order": 1,
            "content": "..."
          }
        ],
        "answered": false
      }
    ],
    "createdAt": "2026-11-02T11:20:00Z"
  }
}
```

보안 규칙:

- 제출 전 `referenceAnswer`, `explanation`, `gradingRubric`, 선택지 `isCorrect`를 반환하지 않는다.
- 문제 근거는 문제 풀이 전에 표시할지 제출 후에만 표시할지 UX 정책에서 확정한다. 기본안은 제출 후 표시다.

## 9.4 문제 단건 조회

`GET /questions/{questionId}`

인증: 필요

성공: `200 OK`

응답은 문제 묶음의 개별 문제 형식과 같다. 이미 답안을 제출한 경우 최근 시도 요약을 추가할 수 있다.

## 10. 답안 제출 API

## 10.1 문제 답안 제출

`POST /questions/{questionId}/attempts`

인증: 필요  
`Idempotency-Key`: 필수

객관식 요청:

```json
{
  "selectedChoiceId": "068162b6-5028-4cd0-85d1-a98202fcc9f7",
  "confused": false
}
```

주관식 요청:

```json
{
  "submittedText": "트랜잭션 격리 수준은 동시에 실행되는 트랜잭션의 ...",
  "confused": true
}
```

객관식 성공: `201 Created`

```json
{
  "data": {
    "attemptId": "c76ffc3b-7185-4fc8-b568-c79f6454153d",
    "attemptNo": 1,
    "gradingStatus": "COMPLETED",
    "correct": false,
    "score": 0,
    "correctChoiceId": "62da2de2-a642-4d55-b945-ad9673796f5b",
    "referenceAnswer": "격리 수준은 ...",
    "explanation": "선택한 설명은 Read Committed와 Repeatable Read를 ...",
    "citations": [
      {
        "pageStart": 37,
        "pageEnd": 38,
        "quote": "..."
      }
    ],
    "reviewItem": {
      "reviewItemId": "eb10cc1f-16b2-4070-9909-a0d266f3cc1c",
      "reason": "WRONG",
      "status": "OPEN"
    },
    "submittedAt": "2026-11-02T11:30:00Z"
  }
}
```

주관식 접수 성공: `202 Accepted`

```json
{
  "data": {
    "attemptId": "c76ffc3b-7185-4fc8-b568-c79f6454153d",
    "attemptNo": 1,
    "gradingStatus": "PENDING",
    "jobId": "c8eb38a7-6825-45ee-966a-a9a184a86dba"
  }
}
```

오류:

- `400 ANSWER_TYPE_MISMATCH`
- `404 QUESTION_NOT_FOUND`
- `409 QUIZ_NOT_READY`
- `422 CHOICE_NOT_IN_QUESTION`
- `429 AI_USAGE_LIMIT_EXCEEDED`

## 10.2 풀이 결과 조회

`GET /attempts/{attemptId}`

인증: 필요

주관식 채점 완료 성공: `200 OK`

```json
{
  "data": {
    "attemptId": "c76ffc3b-7185-4fc8-b568-c79f6454153d",
    "questionId": "f20f230b-4825-4550-8469-f7a016746168",
    "attemptNo": 1,
    "gradingStatus": "COMPLETED",
    "evaluationType": "AI",
    "correct": false,
    "score": 65,
    "submittedText": "트랜잭션 격리 수준은 ...",
    "referenceAnswer": "격리 수준은 동시 실행 시 허용할 현상을 ...",
    "feedback": "기본 정의는 맞지만 Dirty Read와 Non-repeatable Read 구분이 빠졌습니다.",
    "missingKeywords": [
      "Dirty Read",
      "Non-repeatable Read"
    ],
    "citations": [
      {
        "pageStart": 37,
        "pageEnd": 38,
        "quote": "..."
      }
    ],
    "submittedAt": "2026-11-02T11:30:00Z",
    "gradedAt": "2026-11-02T11:30:08Z"
  }
}
```

> 주관식 결과는 정답 확정이 아니라 `AI 평가`임을 화면과 API 필드로 명확히 표시한다.

## 10.3 문제별 풀이 이력 조회

`GET /questions/{questionId}/attempts?cursor={cursor}&size={size}`

인증: 필요

성공: `200 OK`

각 항목은 `attemptId`, `attemptNo`, 제출 답안, 정오 여부, 점수, 제출 시각을 포함한다.

## 11. 오답·헷갈림 API

## 11.1 오답 목록 조회

`GET /reviews?status={status}&reason={reason}&documentId={documentId}&cursor={cursor}&size={size}`

인증: 필요

쿼리:

| 파라미터 | 필수 | 설명 |
|---|---:|---|
| `status` | N | `OPEN`, `MASTERED`, `ARCHIVED` |
| `reason` | N | `WRONG`, `CONFUSED`, `BOTH` |
| `documentId` | N | 특정 문서 필터 |
| `cursor` | N | 페이지 커서 |
| `size` | N | 기본 20, 최대 100 |

성공: `200 OK`

```json
{
  "data": [
    {
      "reviewItemId": "eb10cc1f-16b2-4070-9909-a0d266f3cc1c",
      "document": {
        "documentId": "b4d0b274-3f49-46de-a834-35cae401fb03",
        "fileName": "정보처리기사.pdf"
      },
      "question": {
        "questionId": "f20f230b-4825-4550-8469-f7a016746168",
        "questionType": "MCQ",
        "questionText": "다음 중 격리 수준에 대한 설명으로 옳은 것은?"
      },
      "reason": "WRONG",
      "status": "OPEN",
      "retryCount": 0,
      "latestResult": {
        "correct": false,
        "score": 0
      },
      "updatedAt": "2026-11-02T11:30:00Z"
    }
  ],
  "page": {
    "nextCursor": null,
    "hasNext": false,
    "size": 20
  }
}
```

## 11.2 오답 상태 수정

`PATCH /reviews/{reviewItemId}`

인증: 필요

요청:

```json
{
  "confused": true,
  "status": "OPEN"
}
```

성공: `200 OK`

```json
{
  "data": {
    "reviewItemId": "eb10cc1f-16b2-4070-9909-a0d266f3cc1c",
    "reason": "BOTH",
    "status": "OPEN",
    "updatedAt": "2026-11-02T11:35:00Z"
  }
}
```

## 11.3 오답 재도전

`POST /reviews/{reviewItemId}/attempts`

인증: 필요  
`Idempotency-Key`: 필수

요청은 문제 답안 제출과 동일하다. 서버는 연결된 `questionId`를 확인하고 새 `question_attempts`를 생성한다.

성공:

- 객관식: `201 Created`
- 주관식: `202 Accepted`

재도전 정답 시 기본 정책:

- `retryCount`를 증가시킨다.
- 최근 재도전이 정답이면 `review_items.status`를 `MASTERED`로 변경한다.
- 사용자는 다시 `OPEN`으로 변경할 수 있다.

## 12. 내 정보 API

## 12.1 내 정보 조회

`GET /users/me`

인증: 필요

성공: `200 OK`

```json
{
  "data": {
    "userId": "0a68688f-013d-4622-8cba-e3e7d31316df",
    "email": "learner@example.com",
    "displayName": "학습자",
    "role": "USER",
    "createdAt": "2026-11-02T10:30:00Z"
  }
}
```

## 12.2 표시 이름 변경

`PATCH /users/me`

인증: 필요

요청:

```json
{
  "displayName": "백엔드 학습자"
}
```

성공: `200 OK`

## 12.3 회원 탈퇴

`DELETE /users/me`

인증: 필요  
`Idempotency-Key`: 필수

요청:

```json
{
  "password": "StrongPassword!23"
}
```

성공: `202 Accepted`

```json
{
  "data": {
    "status": "WITHDRAWAL_ACCEPTED",
    "requestedAt": "2026-11-02T12:00:00Z"
  }
}
```

정책:

- 인증 토큰을 즉시 폐기한다.
- 사용자 문서와 파생 데이터를 비동기로 삭제한다.

## 13. 상태 확인 API

## 13.1 애플리케이션 상태

`GET /actuator/health`

인증: 운영 정책에 따라 상세 정보 제한

성공: `200 OK`

```json
{
  "status": "UP"
}
```

> 데이터베이스·메시지 브로커·외부 AI 제공자의 상세 상태는 관리자 또는 내부 네트워크에만 노출한다.

## 14. 오류 코드 목록

### 14.1 인증·사용자

| 코드 | HTTP | 설명 |
|---|---:|---|
| `AUTH_0001` | 401 | 이메일 또는 비밀번호 불일치 |
| `AUTH_0002` | 401 | 유효하지 않은 Access Token |
| `AUTH_0003` | 401 | Access Token 만료 |
| `AUTH_0004` | 401 | 유효하지 않은 Refresh Token |
| `AUTH_0005` | 401 | Refresh Token 만료 |
| `AUTH_0006` | 401 | 이미 회전·폐기된 Refresh Token 재사용 |
| `AUTH_0007` | 401 | 잠긴 계정 |
| `AUTH_0008` | 401 | 인증이 필요한 요청 |
| `USER_0001` | 409 | 이미 가입된 이메일 |
| `USER_0002` | 401 | 탈퇴한 계정 |
| `USER_0003` | 400 | 현재 비밀번호 불일치 |
| `USER_0004` | 400 | 기존 비밀번호와 같은 새 비밀번호 |

### 14.2 문서·작업

| 코드 | HTTP | 설명 |
|---|---:|---|
| `DOCUMENT_NOT_FOUND` | 404 | 문서 없음 또는 소유권 없음 |
| `DOCUMENT_NOT_READY` | 409 | RAG 처리가 완료되지 않음 |
| `DUPLICATE_DOCUMENT` | 409 | 동일 파일 중복 업로드 |
| `FILE_TOO_LARGE` | 413 | 파일 크기 제한 초과 |
| `PDF_ONLY` | 415 | PDF가 아닌 파일 |
| `PDF_ENCRYPTED` | 422 | 암호화된 PDF |
| `PDF_DAMAGED` | 422 | 손상된 PDF |
| `PDF_TEXT_NOT_FOUND` | 422 | 추출 가능한 텍스트 없음, OCR 미지원 |
| `PDF_PAGE_LIMIT_EXCEEDED` | 422 | 페이지 제한 초과 |
| `JOB_NOT_FOUND` | 404 | 작업 없음 또는 소유권 없음 |
| `JOB_NOT_FAILED` | 409 | 실패 상태가 아닌 작업 재시도 |
| `JOB_NOT_RETRYABLE` | 409 | 재시도 불가능한 오류 |

### 14.3 AI·학습

| 코드 | HTTP | 설명 |
|---|---:|---|
| `SUMMARY_NOT_FOUND` | 404 | 요약 없음 또는 소유권 없음 |
| `QUIZ_SET_NOT_FOUND` | 404 | 문제 묶음 없음 또는 소유권 없음 |
| `QUESTION_NOT_FOUND` | 404 | 문제 없음 또는 소유권 없음 |
| `ATTEMPT_NOT_FOUND` | 404 | 풀이 없음 또는 소유권 없음 |
| `REVIEW_ITEM_NOT_FOUND` | 404 | 오답 항목 없음 또는 소유권 없음 |
| `INVALID_QUESTION_COUNT` | 400 | 문제 개수 범위 오류 |
| `ANSWER_TYPE_MISMATCH` | 400 | 문제 유형과 답안 유형 불일치 |
| `CHOICE_NOT_IN_QUESTION` | 422 | 해당 문제의 선택지가 아님 |
| `INSUFFICIENT_EVIDENCE` | 422 | 문서 근거 부족으로 생성 불가 |
| `AI_PROVIDER_TIMEOUT` | 502 | AI 제공자 응답 시간 초과 |
| `AI_PROVIDER_ERROR` | 502 | AI 제공자 오류 |
| `AI_USAGE_LIMIT_EXCEEDED` | 429 | AI 사용량 한도 초과 |

## 15. 멱등성 규칙

적용 API:

- `POST /documents`
- `DELETE /documents/{documentId}`
- `POST /jobs/{jobId}/retry`
- `POST /documents/{documentId}/summaries`
- `POST /documents/{documentId}/quiz-sets`
- `POST /questions/{questionId}/attempts`
- `POST /reviews/{reviewItemId}/attempts`
- `DELETE /users/me`

처리 규칙:

1. 클라이언트는 요청 단위 UUID를 `Idempotency-Key`로 보낸다.
2. 서버는 인증 사용자, API 경로, 키와 요청 본문 해시를 함께 저장·검증한다.
3. 같은 키와 같은 요청은 최초 응답을 재사용한다.
4. 같은 키에 다른 요청 본문을 보내면 `409 IDEMPOTENCY_KEY_CONFLICT`를 반환한다.
5. 키 보존 기간은 최소 24시간을 권장하며 운영 정책에서 확정한다.

## 16. 접근 권한 규칙

| 리소스 | 접근 조건 |
|---|---|
| 문서 | `documents.user_id = authenticatedUserId` |
| 작업 | `processing_jobs.user_id = authenticatedUserId` |
| 요약 | `summaries.user_id = authenticatedUserId` |
| 문제 묶음 | `quiz_sets.user_id = authenticatedUserId` |
| 문제 | 상위 `quiz_sets.user_id = authenticatedUserId` |
| 풀이 | `question_attempts.user_id = authenticatedUserId` |
| 오답 | `review_items.user_id = authenticatedUserId` |

타인 리소스 접근은 관리자 기능이 아닌 이상 `403` 대신 `404`를 반환해 존재 여부를 숨긴다.

## 17. 구현 전 확정 항목

- PDF 서명 URL 유효 시간과 다운로드 허용 정책
- 중복 문서를 오류로 막을지 기존 문서 안내 후 재사용하게 할지
- 문제 풀이 전에 근거 페이지를 노출할지 여부
- 주관식 채점 완료 판정 기준과 `correct` 임계 점수
- API Rate Limit과 사용자별 AI 사용량 한도
- 멱등 키와 응답 캐시 보존 기간
- 관리자·운영용 API를 MVP 공개 API와 분리할 방식
- OpenAPI에서 AI 모델·프롬프트 버전을 관리자 전용으로 노출할지 여부


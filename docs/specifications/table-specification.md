# AiProfessor MVP 테이블 명세서

- 문서 버전: v0.1 (초안)
- 작성일: 2026-11-02
- 기준 문서: `AiProfessor MVP 요구사항 명세서 v0.1`, `인증 및 토큰 정책 v0.1`
- 용도: Notion 붙여넣기 및 ERD·DDL 설계 기준

## 1. 설계 전제

| 항목 | 권장 기본안 | 비고 |
|---|---|---|
| 관계형 DB | PostgreSQL | 최종 기술 선택 ADR에서 확정 |
| 벡터 저장 | PostgreSQL + pgvector | 외부 벡터 DB 선택 시 `chunk_embeddings`의 저장 위치만 교체 |
| PK | UUID | 외부에 노출되는 식별자 추측 방지 |
| 테이블·컬럼명 | 영문 snake_case | 테이블명은 복수형 사용 |
| 시간 타입 | `TIMESTAMPTZ` | 저장·API 전달은 UTC, 화면에서 사용자 시간대로 변환 |
| 긴 본문 | `TEXT` | PDF 추출문, 요약, 문제, 해설 등에 사용 |
| 구조화 가변 데이터 | `JSONB` | 채점 기준, 모델 설정 등 제한적으로 사용 |
| 금액 | `NUMERIC(18, 6)` | AI 비용 추정값 저장 |
| 삭제 정책 | 소유권 데이터는 논리 삭제 후 비동기 물리 삭제 | 사용자에게는 삭제 즉시 접근 불가 |

> `VECTOR(n)`의 차원 `n`은 선택한 임베딩 모델에 따라 달라지므로 DDL 작성 시 확정한다.

## 2. 공통 규칙

### 2.1 공통 컬럼

| 컬럼명 | 타입 | 설명 |
|---|---|---|
| `id` | UUID | 기본 키 |
| `created_at` | TIMESTAMPTZ | 생성 시각 |
| `updated_at` | TIMESTAMPTZ | 마지막 변경 시각 |

### 2.2 데이터 규칙

- 이메일은 소문자 정규화 후 저장한다.
- 비밀번호, Refresh Token, 파일 원문, API 키는 평문으로 저장하지 않는다.
- 공개 API 응답에서는 내부 저장 키, 비밀번호 해시, 토큰 해시, 정답 데이터를 노출하지 않는다.
- AI 생성 결과에는 모델, 프롬프트 버전 및 근거 청크 연결을 남긴다.
- 사용자 소유 데이터 조회 시 항상 `user_id` 또는 상위 소유자의 `user_id`를 조건에 포함한다.
- 삭제된 문서는 즉시 조회 대상에서 제외하고 객체 저장소·벡터·파생 데이터는 삭제 작업으로 정리한다.

## 3. 테이블 목록

| 도메인 | 테이블명 | 설명 |
|---|---|---|
| 사용자 | `users` | 사용자 계정과 상태 |
| 인증 | `refresh_tokens` | Refresh Token 회전·폐기 관리 |
| 문서 | `documents` | 업로드 PDF 메타데이터와 처리 상태 |
| 작업 | `processing_jobs` | 비동기 작업의 현재 상태 |
| 작업 | `processing_job_attempts` | 작업 실행·재시도 이력 |
| RAG | `document_chunks` | 페이지 정보를 보존한 문서 청크 |
| RAG | `chunk_embeddings` | 청크 임베딩과 모델 버전 |
| 요약 | `summaries` | 전체·주제 요약 결과 |
| 요약 | `summary_citations` | 요약과 근거 청크 연결 |
| 문제 | `quiz_sets` | 한 번의 문제 생성 요청과 문제 묶음 |
| 문제 | `questions` | 객관식·주관식 문제와 기준 답안 |
| 문제 | `question_choices` | 객관식 선택지와 정답 여부 |
| 문제 | `question_citations` | 문제·정답·해설의 근거 청크 |
| 학습 | `question_attempts` | 문제별 답안 제출과 채점 결과 |
| 학습 | `review_items` | 오답·헷갈림 및 재학습 상태 |
| AI 운영 | `ai_usages` | 모델 호출량·지연·비용 기록 |

## 4. 상세 테이블 명세

## 4.1 `users`

사용자 계정과 권한·상태를 저장한다.

| 컬럼명 | 타입           | NULL | 기본값 | 키·제약 | 설명 |
|---|--------------|---:|---|---|---|
| `id` | UUID         | N | `gen_random_uuid()` | PK | 사용자 ID |
| `email` | VARCHAR(320) | N | - | UK | 소문자로 정규화한 이메일 |
| `password_hash` | VARCHAR(255) | N | - | - | 검증된 해시 알고리즘으로 생성한 비밀번호 해시 |
| `display_name` | VARCHAR(10)  | N | - | - | 화면 표시 이름 |
| `role` | VARCHAR(20)  | N | `USER` | CHECK | `USER`, `ADMIN` |
| `status` | VARCHAR(20)  | N | `ACTIVE` | CHECK | `ACTIVE`, `LOCKED`, `WITHDRAWN` |
| `last_login_at` | TIMESTAMPTZ  | Y | - | - | 마지막 로그인 시각 |
| `withdrawn_at` | TIMESTAMPTZ  | Y | - | - | 탈퇴 요청 시각 |
| `created_at` | TIMESTAMPTZ  | N | `now()` | - | 생성 시각 |
| `updated_at` | TIMESTAMPTZ  | N | `now()` | - | 변경 시각 |

인덱스·제약:

- `UK users(email)`
- 이메일 비교는 소문자 정규화 기준으로 수행한다.
- 탈퇴 사용자는 즉시 인증과 데이터 접근을 차단한다.

## 4.2 `refresh_tokens`

Refresh Token 원문 대신 해시를 저장하고 토큰 회전과 로그아웃을 지원한다.

| 컬럼명 | 타입 | NULL | 기본값 | 키·제약 | 설명 |
|---|---|---:|---|---|---|
| `id` | UUID | N | `gen_random_uuid()` | PK | 토큰 레코드 ID |
| `user_id` | UUID | N | - | FK | `users.id` |
| `token_hash` | CHAR(64) | N | - | UK | Refresh Token 원문의 SHA-256 소문자 16진수 해시 |
| `token_family_id` | UUID | N | - | INDEX | 회전 토큰 묶음 ID |
| `expires_at` | TIMESTAMPTZ | N | - | INDEX | 만료 시각 |
| `revoked_at` | TIMESTAMPTZ | Y | - | - | 폐기 시각 |
| `replaced_by_token_id` | UUID | Y | - | FK | 새로 발급된 `refresh_tokens.id` |
| `created_ip_hash` | CHAR(64) | Y | - | - | 서버 비밀값을 사용한 발급 IP의 HMAC-SHA-256 소문자 16진수 해시 |
| `user_agent` | VARCHAR(500) | Y | - | - | 발급 클라이언트 정보 |
| `created_at` | TIMESTAMPTZ | N | `now()` | - | 발급 시각 |

인덱스·제약:

- `UK refresh_tokens(token_hash)`
- `INDEX refresh_tokens(user_id, revoked_at, expires_at)`
- `INDEX refresh_tokens(token_family_id, revoked_at)`
- 토큰 갱신 시 `token_hash`로 레코드를 조회하고 행 잠금 또는 동등한 원자적 갱신으로 중복 성공을 막는다.
- 사용자 탈퇴 또는 전체 로그아웃 시 해당 사용자의 활성 토큰을 모두 폐기한다.

## 4.3 `documents`

업로드한 PDF의 소유자, 저장 위치, 검증 정보 및 처리 상태를 저장한다.

| 컬럼명 | 타입 | NULL | 기본값 | 키·제약 | 설명 |
|---|---|---:|---|---|---|
| `id` | UUID | N | `gen_random_uuid()` | PK | 문서 ID |
| `user_id` | UUID | N | - | FK | `users.id` |
| `original_file_name` | VARCHAR(255) | N | - | - | 사용자가 업로드한 파일명 |
| `storage_key` | VARCHAR(500) | N | - | UK | 객체 저장소 내부 키, API 미노출 |
| `mime_type` | VARCHAR(100) | N | - | CHECK | MVP는 `application/pdf`만 허용 |
| `file_size_bytes` | BIGINT | N | - | CHECK | 0 초과, 기본 최대 50MB |
| `page_count` | INTEGER | Y | - | CHECK | 추출 후 확정, 기본 최대 300페이지 |
| `sha256` | CHAR(64) | N | - | - | 중복 탐지용 파일 해시 |
| `status` | VARCHAR(30) | N | `UPLOADED` | CHECK, INDEX | 문서 처리 상태 |
| `failure_code` | VARCHAR(100) | Y | - | - | 사용자 대응이 가능한 실패 코드 |
| `failure_message` | VARCHAR(500) | Y | - | - | 민감정보를 제거한 실패 설명 |
| `processed_at` | TIMESTAMPTZ | Y | - | - | `READY` 완료 시각 |
| `deleted_at` | TIMESTAMPTZ | Y | - | INDEX | 논리 삭제 시각 |
| `created_at` | TIMESTAMPTZ | N | `now()` | - | 업로드 시각 |
| `updated_at` | TIMESTAMPTZ | N | `now()` | - | 변경 시각 |

상태값:

- `UPLOADED`, `EXTRACTING`, `CHUNKING`, `EMBEDDING`, `READY`, `FAILED`, `DELETING`, `DELETED`

인덱스·제약:

- `INDEX documents(user_id, created_at DESC) WHERE deleted_at IS NULL`
- `INDEX documents(user_id, status) WHERE deleted_at IS NULL`
- `UK documents(user_id, sha256) WHERE deleted_at IS NULL`은 중복을 차단할 때 사용한다. 경고만 제공하려면 일반 인덱스로 변경한다.

## 4.4 `processing_jobs`

PDF 처리, 요약 생성, 문제 생성, 주관식 채점 및 삭제 작업의 현재 상태를 저장한다.

| 컬럼명 | 타입 | NULL | 기본값 | 키·제약 | 설명 |
|---|---|---:|---|---|---|
| `id` | UUID | N | `gen_random_uuid()` | PK | 작업 ID |
| `user_id` | UUID | N | - | FK | 소유자 `users.id` |
| `document_id` | UUID | Y | - | FK | 대상 `documents.id` |
| `job_type` | VARCHAR(30) | N | - | CHECK, INDEX | 작업 종류 |
| `target_type` | VARCHAR(30) | N | - | CHECK | `DOCUMENT`, `SUMMARY`, `QUIZ_SET`, `QUESTION_ATTEMPT` |
| `target_id` | UUID | N | - | INDEX | 대상 엔티티 ID |
| `idempotency_key` | VARCHAR(100) | N | - | UK | 중복 요청 방지 키 |
| `status` | VARCHAR(20) | N | `PENDING` | CHECK, INDEX | 작업 상태 |
| `current_step` | VARCHAR(50) | Y | - | - | 현재 처리 단계 |
| `progress_percent` | SMALLINT | N | `0` | CHECK | 0~100, 추정 진행률 |
| `retry_count` | SMALLINT | N | `0` | CHECK | 완료된 재시도 횟수 |
| `max_retries` | SMALLINT | N | `3` | CHECK | 최대 자동 재시도 횟수 |
| `error_code` | VARCHAR(100) | Y | - | - | 실패 코드 |
| `error_message` | VARCHAR(500) | Y | - | - | 민감정보를 제거한 실패 메시지 |
| `next_retry_at` | TIMESTAMPTZ | Y | - | INDEX | 다음 자동 재시도 예정 시각 |
| `started_at` | TIMESTAMPTZ | Y | - | - | 최초 실행 시작 시각 |
| `completed_at` | TIMESTAMPTZ | Y | - | - | 성공·최종 실패 시각 |
| `created_at` | TIMESTAMPTZ | N | `now()` | - | 작업 접수 시각 |
| `updated_at` | TIMESTAMPTZ | N | `now()` | - | 변경 시각 |

작업 종류:

- `PROCESS_DOCUMENT`, `GENERATE_SUMMARY`, `GENERATE_QUIZ`, `GRADE_SAQ`, `DELETE_DOCUMENT`

작업 상태:

- `PENDING`, `RUNNING`, `RETRY_WAIT`, `SUCCEEDED`, `FAILED`, `CANCELLED`

인덱스·제약:

- `UK processing_jobs(idempotency_key)`
- `INDEX processing_jobs(status, next_retry_at)`
- `INDEX processing_jobs(user_id, created_at DESC)`
- `target_type + target_id`는 다형 참조이므로 애플리케이션 서비스에서 대상 존재 여부와 소유권을 검증한다.

## 4.5 `processing_job_attempts`

각 작업의 실제 실행과 재시도 이력을 저장한다.

| 컬럼명 | 타입 | NULL | 기본값 | 키·제약 | 설명 |
|---|---|---:|---|---|---|
| `id` | UUID | N | `gen_random_uuid()` | PK | 실행 이력 ID |
| `job_id` | UUID | N | - | FK | `processing_jobs.id` |
| `attempt_no` | SMALLINT | N | - | UK 일부 | 1부터 시작하는 실행 순번 |
| `worker_id` | VARCHAR(100) | Y | - | - | 처리 워커 식별자 |
| `status` | VARCHAR(20) | N | `RUNNING` | CHECK | `RUNNING`, `SUCCEEDED`, `FAILED` |
| `error_code` | VARCHAR(100) | Y | - | - | 실행 실패 코드 |
| `error_message` | VARCHAR(500) | Y | - | - | 민감정보 제거 메시지 |
| `started_at` | TIMESTAMPTZ | N | `now()` | - | 실행 시작 시각 |
| `finished_at` | TIMESTAMPTZ | Y | - | - | 실행 종료 시각 |

인덱스·제약:

- `UK processing_job_attempts(job_id, attempt_no)`
- `INDEX processing_job_attempts(job_id, started_at DESC)`

## 4.6 `document_chunks`

PDF의 페이지와 순서를 보존한 검색 단위 텍스트를 저장한다.

| 컬럼명 | 타입 | NULL | 기본값 | 키·제약 | 설명 |
|---|---|---:|---|---|---|
| `id` | UUID | N | `gen_random_uuid()` | PK | 청크 ID |
| `document_id` | UUID | N | - | FK | `documents.id` |
| `chunk_index` | INTEGER | N | - | UK 일부 | 문서 내 청크 순번 |
| `page_start` | INTEGER | N | - | CHECK | 시작 페이지, 1부터 시작 |
| `page_end` | INTEGER | N | - | CHECK | 종료 페이지 |
| `content` | TEXT | N | - | - | 추출·정제한 청크 본문 |
| `token_count` | INTEGER | Y | - | CHECK | 사용 토크나이저 기준 토큰 수 |
| `content_hash` | CHAR(64) | N | - | INDEX | 청크 중복·변경 탐지 |
| `chunk_strategy` | VARCHAR(50) | N | - | - | 청킹 전략 이름 |
| `chunk_size` | INTEGER | N | - | - | 전략의 목표 크기 |
| `chunk_overlap` | INTEGER | N | `0` | - | 겹침 크기 |
| `created_at` | TIMESTAMPTZ | N | `now()` | - | 생성 시각 |

인덱스·제약:

- `UK document_chunks(document_id, chunk_index)`
- `INDEX document_chunks(document_id, page_start)`
- `CHECK page_start >= 1 AND page_end >= page_start`
- 문서 삭제 시 함께 삭제한다.

## 4.7 `chunk_embeddings`

청크별 임베딩과 모델 버전을 저장한다. 외부 벡터 DB를 사용하면 `external_vector_id` 중심으로 운용할 수 있다.

| 컬럼명 | 타입 | NULL | 기본값 | 키·제약 | 설명 |
|---|---|---:|---|---|---|
| `id` | UUID | N | `gen_random_uuid()` | PK | 임베딩 ID |
| `chunk_id` | UUID | N | - | FK | `document_chunks.id` |
| `provider` | VARCHAR(50) | N | - | - | 임베딩 제공자 |
| `model` | VARCHAR(100) | N | - | - | 임베딩 모델 |
| `model_version` | VARCHAR(100) | N | - | - | 모델·설정 버전 |
| `dimensions` | INTEGER | N | - | CHECK | 벡터 차원 |
| `embedding` | VECTOR(n) | Y | - | VECTOR INDEX | pgvector 사용 시 실제 벡터 |
| `external_vector_id` | VARCHAR(255) | Y | - | UK | 외부 벡터 DB 레코드 ID |
| `created_at` | TIMESTAMPTZ | N | `now()` | - | 생성 시각 |

인덱스·제약:

- `UK chunk_embeddings(chunk_id, provider, model_version)`
- pgvector 사용 시 코사인 거리 기준 HNSW 또는 IVFFlat 인덱스를 성능 실험 후 선택한다.
- `embedding`과 `external_vector_id` 중 선택한 저장 방식에 필요한 값은 반드시 존재해야 한다.

## 4.8 `summaries`

문서 전체 또는 특정 주제에 대한 요약 생성 결과를 저장한다.

| 컬럼명 | 타입 | NULL | 기본값 | 키·제약 | 설명 |
|---|---|---:|---|---|---|
| `id` | UUID | N | `gen_random_uuid()` | PK | 요약 ID |
| `document_id` | UUID | N | - | FK | `documents.id` |
| `user_id` | UUID | N | - | FK | 소유자 `users.id` |
| `job_id` | UUID | Y | - | FK | 생성 작업 `processing_jobs.id` |
| `summary_type` | VARCHAR(20) | N | - | CHECK | `FULL`, `TOPIC` |
| `topic` | VARCHAR(255) | Y | - | - | 주제 요약의 사용자 입력 |
| `title` | VARCHAR(255) | N | - | - | 요약 제목 |
| `content` | TEXT | Y | - | - | 완료된 요약 본문 |
| `status` | VARCHAR(20) | N | `PENDING` | CHECK, INDEX | `PENDING`, `GENERATING`, `READY`, `FAILED` |
| `insufficient_evidence` | BOOLEAN | N | `false` | - | 근거 부족 응답 여부 |
| `provider` | VARCHAR(50) | Y | - | - | 생성 모델 제공자 |
| `model` | VARCHAR(100) | Y | - | - | 생성 모델 |
| `prompt_version` | VARCHAR(50) | Y | - | - | 프롬프트 버전 |
| `generation_config` | JSONB | Y | - | - | temperature 등 재현용 설정 |
| `failure_code` | VARCHAR(100) | Y | - | - | 실패 코드 |
| `created_at` | TIMESTAMPTZ | N | `now()` | - | 요청 시각 |
| `updated_at` | TIMESTAMPTZ | N | `now()` | - | 변경 시각 |

인덱스·제약:

- `INDEX summaries(document_id, created_at DESC)`
- `INDEX summaries(user_id, status)`
- `summary_type = 'TOPIC'`이면 `topic`이 필요하다.

## 4.9 `summary_citations`

요약의 특정 문장 또는 구간과 근거 청크를 연결한다.

| 컬럼명 | 타입 | NULL | 기본값 | 키·제약 | 설명 |
|---|---|---:|---|---|---|
| `id` | UUID | N | `gen_random_uuid()` | PK | 인용 ID |
| `summary_id` | UUID | N | - | FK | `summaries.id` |
| `chunk_id` | UUID | N | - | FK | `document_chunks.id` |
| `citation_order` | INTEGER | N | - | - | 요약 내 표시 순서 |
| `claim_text` | TEXT | Y | - | - | 이 근거가 뒷받침하는 요약 문장 |
| `quote_text` | TEXT | N | - | - | 사용자에게 보여줄 짧은 근거 문맥 |
| `page_start` | INTEGER | N | - | - | 비정규화한 빠른 표시용 페이지 |
| `page_end` | INTEGER | N | - | - | 종료 페이지 |
| `created_at` | TIMESTAMPTZ | N | `now()` | - | 생성 시각 |

인덱스·제약:

- `UK summary_citations(summary_id, citation_order)`
- `INDEX summary_citations(summary_id)`
- 인용 청크는 요약과 동일한 문서에 속해야 한다.

## 4.10 `quiz_sets`

한 번의 문제 생성 요청과 생성된 문제 묶음의 조건을 저장한다.

| 컬럼명 | 타입 | NULL | 기본값 | 키·제약 | 설명 |
|---|---|---:|---|---|---|
| `id` | UUID | N | `gen_random_uuid()` | PK | 문제 묶음 ID |
| `document_id` | UUID | N | - | FK | `documents.id` |
| `user_id` | UUID | N | - | FK | 소유자 `users.id` |
| `job_id` | UUID | Y | - | FK | 생성 작업 `processing_jobs.id` |
| `topic` | VARCHAR(255) | Y | - | - | 선택 주제, NULL이면 문서 전체 |
| `difficulty` | VARCHAR(20) | N | - | CHECK | `EASY`, `MEDIUM`, `HARD` |
| `mcq_count` | SMALLINT | N | `0` | CHECK | 객관식 요청 개수 |
| `saq_count` | SMALLINT | N | `0` | CHECK | 주관식 요청 개수 |
| `status` | VARCHAR(20) | N | `PENDING` | CHECK, INDEX | `PENDING`, `GENERATING`, `READY`, `FAILED` |
| `provider` | VARCHAR(50) | Y | - | - | 생성 제공자 |
| `model` | VARCHAR(100) | Y | - | - | 생성 모델 |
| `prompt_version` | VARCHAR(50) | Y | - | - | 프롬프트 버전 |
| `failure_code` | VARCHAR(100) | Y | - | - | 실패 코드 |
| `created_at` | TIMESTAMPTZ | N | `now()` | - | 생성 요청 시각 |
| `updated_at` | TIMESTAMPTZ | N | `now()` | - | 변경 시각 |

인덱스·제약:

- `CHECK mcq_count >= 0 AND saq_count >= 0 AND mcq_count + saq_count BETWEEN 1 AND 20`
- `INDEX quiz_sets(document_id, created_at DESC)`

## 4.11 `questions`

생성된 객관식·주관식 문제, 기준 답안, 해설 및 채점 기준을 저장한다.

| 컬럼명 | 타입 | NULL | 기본값 | 키·제약 | 설명 |
|---|---|---:|---|---|---|
| `id` | UUID | N | `gen_random_uuid()` | PK | 문제 ID |
| `quiz_set_id` | UUID | N | - | FK | `quiz_sets.id` |
| `document_id` | UUID | N | - | FK | 조회 최적화·무결성 검증용 문서 ID |
| `question_order` | SMALLINT | N | - | UK 일부 | 문제 묶음 내 순서 |
| `question_type` | VARCHAR(10) | N | - | CHECK | `MCQ`, `SAQ` |
| `difficulty` | VARCHAR(20) | N | - | CHECK | `EASY`, `MEDIUM`, `HARD` |
| `topic` | VARCHAR(255) | Y | - | - | 문제 주제 |
| `question_text` | TEXT | N | - | - | 문제 본문 |
| `reference_answer` | TEXT | N | - | API 제한 | 주관식 기준 답안 또는 객관식 정답 설명 |
| `explanation` | TEXT | N | - | API 제한 | 정답 제출 후 보여줄 해설 |
| `grading_rubric` | JSONB | Y | - | - | 주관식 핵심어·배점·판정 기준 |
| `provider` | VARCHAR(50) | N | - | - | 생성 제공자 |
| `model` | VARCHAR(100) | N | - | - | 생성 모델 |
| `prompt_version` | VARCHAR(50) | N | - | - | 프롬프트 버전 |
| `created_at` | TIMESTAMPTZ | N | `now()` | - | 생성 시각 |

인덱스·제약:

- `UK questions(quiz_set_id, question_order)`
- `INDEX questions(document_id, topic)`
- 문제 조회 API는 제출 전 `reference_answer`, `explanation`, `grading_rubric`을 노출하지 않는다.

## 4.12 `question_choices`

객관식 선택지를 저장한다.

| 컬럼명 | 타입 | NULL | 기본값 | 키·제약 | 설명 |
|---|---|---:|---|---|---|
| `id` | UUID | N | `gen_random_uuid()` | PK | 선택지 ID |
| `question_id` | UUID | N | - | FK | `questions.id` |
| `choice_order` | SMALLINT | N | - | UK 일부 | 표시 순서 |
| `content` | TEXT | N | - | - | 선택지 본문 |
| `is_correct` | BOOLEAN | N | `false` | API 제한 | 정답 여부 |
| `created_at` | TIMESTAMPTZ | N | `now()` | - | 생성 시각 |

인덱스·제약:

- `UK question_choices(question_id, choice_order)`
- 객관식 문제마다 최소 2개 선택지와 정확히 1개의 정답을 가져야 한다. 이 규칙은 문제 생성 트랜잭션에서 검증한다.
- 문제 제출 전 API는 `is_correct`를 노출하지 않는다.

## 4.13 `question_citations`

문제, 기준 답안 및 해설의 근거 청크를 저장한다.

| 컬럼명 | 타입 | NULL | 기본값 | 키·제약 | 설명 |
|---|---|---:|---|---|---|
| `id` | UUID | N | `gen_random_uuid()` | PK | 인용 ID |
| `question_id` | UUID | N | - | FK | `questions.id` |
| `chunk_id` | UUID | N | - | FK | `document_chunks.id` |
| `citation_order` | INTEGER | N | - | - | 표시 순서 |
| `quote_text` | TEXT | N | - | - | 짧은 근거 문맥 |
| `page_start` | INTEGER | N | - | - | 시작 페이지 |
| `page_end` | INTEGER | N | - | - | 종료 페이지 |
| `created_at` | TIMESTAMPTZ | N | `now()` | - | 생성 시각 |

인덱스·제약:

- `UK question_citations(question_id, citation_order)`
- 인용 청크는 문제와 동일한 문서에 속해야 한다.

## 4.14 `question_attempts`

사용자의 문제별 답안 제출, 채점 결과와 재도전 이력을 저장한다.

| 컬럼명 | 타입 | NULL | 기본값 | 키·제약 | 설명 |
|---|---|---:|---|---|---|
| `id` | UUID | N | `gen_random_uuid()` | PK | 풀이 시도 ID |
| `question_id` | UUID | N | - | FK | `questions.id` |
| `user_id` | UUID | N | - | FK | `users.id` |
| `attempt_no` | INTEGER | N | - | UK 일부 | 사용자·문제별 시도 순번 |
| `selected_choice_id` | UUID | Y | - | FK | 객관식 선택 답안 |
| `submitted_text` | TEXT | Y | - | - | 주관식 제출 답안 |
| `grading_status` | VARCHAR(20) | N | `COMPLETED` | CHECK | `PENDING`, `GRADING`, `COMPLETED`, `FAILED` |
| `is_correct` | BOOLEAN | Y | - | - | 최종 정오 여부 |
| `score` | NUMERIC(5, 2) | Y | - | CHECK | 0~100 |
| `feedback` | TEXT | Y | - | - | 채점 피드백 |
| `missing_keywords` | JSONB | Y | - | - | 주관식에서 부족한 핵심어 목록 |
| `provider` | VARCHAR(50) | Y | - | - | 주관식 채점 제공자 |
| `model` | VARCHAR(100) | Y | - | - | 주관식 채점 모델 |
| `prompt_version` | VARCHAR(50) | Y | - | - | 채점 프롬프트 버전 |
| `submitted_at` | TIMESTAMPTZ | N | `now()` | - | 제출 시각 |
| `graded_at` | TIMESTAMPTZ | Y | - | - | 채점 완료 시각 |

인덱스·제약:

- `UK question_attempts(user_id, question_id, attempt_no)`
- `INDEX question_attempts(user_id, submitted_at DESC)`
- 객관식은 `selected_choice_id`, 주관식은 `submitted_text`가 필요하다.
- 사용자는 본인 소유 문서에서 생성된 문제에만 답안을 제출할 수 있다.

## 4.15 `review_items`

오답 및 사용자가 헷갈림으로 표시한 문제의 재학습 상태를 저장한다.

| 컬럼명 | 타입 | NULL | 기본값 | 키·제약 | 설명 |
|---|---|---:|---|---|---|
| `id` | UUID | N | `gen_random_uuid()` | PK | 오답 항목 ID |
| `user_id` | UUID | N | - | FK | `users.id` |
| `question_id` | UUID | N | - | FK | `questions.id` |
| `reason` | VARCHAR(20) | N | - | CHECK | `WRONG`, `CONFUSED`, `BOTH` |
| `status` | VARCHAR(20) | N | `OPEN` | CHECK | `OPEN`, `MASTERED`, `ARCHIVED` |
| `latest_attempt_id` | UUID | Y | - | FK | 최근 `question_attempts.id` |
| `retry_count` | INTEGER | N | `0` | CHECK | 오답 등록 후 재도전 횟수 |
| `last_reviewed_at` | TIMESTAMPTZ | Y | - | - | 마지막 재학습 시각 |
| `created_at` | TIMESTAMPTZ | N | `now()` | - | 등록 시각 |
| `updated_at` | TIMESTAMPTZ | N | `now()` | - | 변경 시각 |

인덱스·제약:

- `UK review_items(user_id, question_id)`
- `INDEX review_items(user_id, status, updated_at DESC)`
- 틀린 답안 제출 시 자동 생성 또는 갱신하고, `CONFUSED`는 사용자 요청으로 설정한다.

## 4.16 `ai_usages`

임베딩·요약·문제 생성·주관식 채점 호출의 사용량, 지연 시간 및 비용 추정치를 저장한다.

| 컬럼명 | 타입 | NULL | 기본값 | 키·제약 | 설명 |
|---|---|---:|---|---|---|
| `id` | UUID | N | `gen_random_uuid()` | PK | 사용량 레코드 ID |
| `user_id` | UUID | Y | - | FK | 비용 귀속 사용자 |
| `document_id` | UUID | Y | - | FK | 관련 문서 |
| `job_id` | UUID | Y | - | FK | 관련 작업 |
| `operation` | VARCHAR(30) | N | - | CHECK, INDEX | 호출 목적 |
| `provider` | VARCHAR(50) | N | - | - | AI 제공자 |
| `model` | VARCHAR(100) | N | - | - | 모델명 |
| `prompt_version` | VARCHAR(50) | Y | - | - | 프롬프트 버전 |
| `input_tokens` | INTEGER | N | `0` | CHECK | 입력 토큰 수 |
| `output_tokens` | INTEGER | N | `0` | CHECK | 출력 토큰 수 |
| `latency_ms` | INTEGER | N | - | CHECK | 호출 지연 시간 |
| `estimated_cost` | NUMERIC(18, 6) | Y | - | - | 호출 시점 단가 기준 추정 비용 |
| `currency` | CHAR(3) | Y | `USD` | - | 비용 통화 |
| `success` | BOOLEAN | N | - | - | 호출 성공 여부 |
| `error_code` | VARCHAR(100) | Y | - | - | 실패 코드 |
| `request_id` | VARCHAR(255) | Y | - | INDEX | 제공자 요청 식별자 |
| `created_at` | TIMESTAMPTZ | N | `now()` | INDEX | 호출 시각 |

호출 목적:

- `EMBEDDING`, `SUMMARY`, `QUIZ_GENERATION`, `SAQ_GRADING`, `EVALUATION`

인덱스·제약:

- `INDEX ai_usages(user_id, created_at DESC)`
- `INDEX ai_usages(document_id, operation)`
- 비용은 확정 청구액이 아니라 측정 시점의 추정값임을 표시한다.

## 5. 핵심 관계

```text
users 1 ── N refresh_tokens
users 1 ── N documents
documents 1 ── N processing_jobs
processing_jobs 1 ── N processing_job_attempts

documents 1 ── N document_chunks
document_chunks 1 ── N chunk_embeddings

documents 1 ── N summaries
summaries 1 ── N summary_citations
document_chunks 1 ── N summary_citations

documents 1 ── N quiz_sets
quiz_sets 1 ── N questions
questions 1 ── N question_choices
questions 1 ── N question_citations
document_chunks 1 ── N question_citations

questions 1 ── N question_attempts
users 1 ── N question_attempts
users 1 ── N review_items
questions 1 ── 0..1 review_items per user

processing_jobs 1 ── N ai_usages
```

## 6. 삭제 정책

| 삭제 대상 | 즉시 처리 | 비동기 정리 | 보존 가능 데이터 |
|---|---|---|---|
| 문서 | `deleted_at` 기록, 모든 사용자 API 접근 차단 | 원본 파일, 청크, 벡터, 요약, 문제, 답안, 오답 제거 | 비식별 운영 메트릭은 정책에 따라 보존 가능 |
| 사용자 | 계정 상태를 `WITHDRAWN`으로 변경하고 토큰 폐기 | 모든 문서와 파생 데이터 제거 | 법적·보안상 필요한 최소 감사 데이터만 별도 정책으로 보존 |
| 요약·문제 묶음 | 조회 차단 또는 즉시 물리 삭제 | 연결된 인용·문제 데이터 정리 | AI 사용량은 비식별화 후 보존 가능 |

삭제 작업은 부분 실패할 수 있으므로 `DELETE_DOCUMENT` 작업 상태와 재시도 이력을 남긴다. 외부 저장소 삭제가 완료되기 전에도 사용자는 해당 데이터에 접근할 수 없어야 한다.

## 7. DDL 작성 전 확정 항목

- PostgreSQL 및 pgvector 채택 여부
- 임베딩 모델과 벡터 차원
- 파일 중복 업로드를 차단할지 경고만 제공할지
- 논리 삭제 후 물리 삭제까지의 보존 시간
- Refresh Token 보관 기간과 동시 로그인 허용 정책
- 주관식 채점 점수와 `is_correct` 판정 기준
- 메시지 브로커 전달 ID를 별도 컬럼으로 저장할지 여부
- 테이블 감사 이력을 위한 `created_by`, `updated_by` 필요 여부


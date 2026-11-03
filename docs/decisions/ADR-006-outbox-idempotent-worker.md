# ADR-006: Transactional Outbox와 멱등적 Worker 채택

- 상태: 승인
- 결정일: 2026-11-04
- 적용 범위: DB 상태 변경과 SQS 작업 발행, 중복·재처리 안전성
- 관련 ADR: ADR-002

## 배경

PDF 업로드를 접수할 때 `documents`와 `processing_jobs` 저장 및 SQS 메시지 발행이 필요하다. PostgreSQL 트랜잭션과 SQS 발행은 하나의 원자적 트랜잭션으로 묶이지 않는다.

DB 커밋 후 SQS 발행이 실패하면 작업은 `PENDING`이지만 Worker가 받을 메시지가 없다. 반대로 SQS를 먼저 발행하고 DB 커밋이 실패하면 Worker가 존재하지 않는 작업을 받을 수 있다. 또한 Outbox Publisher와 SQS Standard는 같은 메시지를 두 번 이상 전달할 수 있다.

## 결정 기준

- 커밋된 작업이 메시지 발행 실패로 영구 정체되지 않아야 한다.
- 동일 이벤트가 반복 발행·전달돼도 결과가 중복 생성되지 않아야 한다.
- 메시지 처리 성공과 삭제 사이의 장애 후 안전하게 재처리할 수 있어야 한다.
- 단일 애플리케이션·단일 PostgreSQL 구조에 불필요한 분산 트랜잭션을 도입하지 않아야 한다.

## 검토한 선택지

| 선택지 | 장점 | 단점 | 판단 |
|---|---|---|---|
| DB 커밋 후 SQS 직접 발행 | 구현이 단순함 | 커밋과 발행 사이 유실 구간 존재 | 제외 |
| SQS 발행 후 DB 저장 | 메시지를 먼저 확보 | 존재하지 않는 작업 전달 가능 | 제외 |
| 분산 트랜잭션/XA | 원자성 목표 | SQS와 현실적인 적용이 어렵고 복잡함 | 제외 |
| Transactional Outbox + 멱등적 Worker | 로컬 트랜잭션으로 유실 구간 축소, 재시도 안전 | 테이블·Publisher·정리 정책 추가 | 선택 |
| Saga | 여러 서비스의 장기 비즈니스 보상에 적합 | 현재 단일 DB의 발행 문제에 과도함 | 제외 |

## 결정

비즈니스 변경과 발행할 이벤트를 같은 PostgreSQL 트랜잭션에 저장한다.

```text
Transaction 1
  documents INSERT/UPDATE
  processing_jobs INSERT
  outbox_events INSERT
COMMIT

Outbox Publisher
  PENDING event 조회·점유
  SQS SendMessage
  성공 시 PUBLISHED 기록

Worker
  SQS 수신
  processing_jobs 상태와 idempotency_key 확인
  작업 점유 및 실행
  결과와 SUCCEEDED/FAILED 커밋
  커밋 성공 후 SQS DeleteMessage
```

### Outbox 계약

`outbox_events`는 최소한 다음 값을 가진다.

| 컬럼 | 역할 |
|---|---|
| `id` / `event_id` | 이벤트 고유 식별자 |
| `aggregate_type`, `aggregate_id` | 이벤트 대상 |
| `event_type` | 작업 종류 |
| `payload` | 식별자 중심 JSON payload |
| `status` | `PENDING`, `PUBLISHED`, `FAILED` |
| `attempt_count`, `next_attempt_at` | 발행 재시도 제어 |
| `published_at`, `created_at` | 추적과 정리 기준 |

- Publisher는 미발행 이벤트를 재시도하므로 SQS 중복 발행을 허용한다.
- 초기에는 단일 Publisher로 시작하고, 다중 Publisher가 필요해지면 짧은 DB 트랜잭션에서 `FOR UPDATE SKIP LOCKED` 등으로 이벤트를 점유한다.
- SQS 전송 성공과 `PUBLISHED` 기록 사이의 장애는 중복 발행으로 귀결되며 Worker 멱등성으로 흡수한다.
- 오래된 `PUBLISHED` 이벤트의 보존·삭제 기간은 운영 정책과 장애 조사 요구를 측정한 후 정한다.

### Worker 멱등성

- `processing_jobs.idempotency_key`에 Unique Constraint를 둔다.
- Worker는 `PENDING` 또는 `RETRY_WAIT` 상태의 작업만 원자적으로 `RUNNING`으로 전환한다.
- 이미 `SUCCEEDED`인 작업의 중복 메시지는 부작용 없이 확인 후 삭제한다.
- 청크는 `(document_id, chunk_index)`, 임베딩은 `(chunk_id, provider, model_version)` 같은 자연스러운 유일 제약으로 중복 저장을 막는다.
- 결과 커밋 전 메시지를 삭제하지 않는다.
- 처리 중 Worker가 종료되면 Visibility Timeout 이후 같은 작업을 이어서 재시도하며, 부분 결과를 삭제하거나 upsert하는 전략을 작업 단계별로 정의한다.

이 설계는 물리적인 exactly-once 전달을 주장하지 않는다. 전달과 발행은 중복될 수 있으며, 최종 비즈니스 결과를 멱등하게 만드는 것이 목표다.

### Saga와 Runbook의 경계

| 문제 | 해결 수단 |
|---|---|
| 하나의 DB 변경 실패 | PostgreSQL 트랜잭션 rollback |
| DB 커밋과 SQS 발행 사이 불일치 | Transactional Outbox |
| 메시지 중복·재전달 | 멱등적 Worker와 DB 유일 제약 |
| 여러 서비스의 비즈니스 보상 | Saga, 현재 MVP에는 적용하지 않음 |
| 서버·DB 장애 후 운영자 복구 | Runbook과 백업 복원 |

## 결과

### 긍정적 결과

- 커밋된 작업은 Outbox에 남아 발행을 다시 시도할 수 있다.
- 중복 발행·전달과 Worker 재시작을 정상 흐름으로 다룰 수 있다.
- 장애 지점과 복구 책임을 DB 트랜잭션, Publisher와 Worker로 구분할 수 있다.

### 감수할 결과

- Outbox 테이블, Publisher, 재시도와 정리 작업이 추가된다.
- 메시지는 DB 커밋 직후가 아니라 Publisher polling 이후 전달되므로 짧은 지연이 생긴다.
- 상태 전이와 부분 결과 정리 규칙을 테스트해야 한다.
- 현재 테이블 명세서와 ERD에 `outbox_events`를 추가해야 한다.

## 검증 계획

- DB 커밋 직후 SQS 발행 실패 시 Outbox 재시도로 복구되는지 검증한다.
- SQS 발행 성공 직후 Publisher를 종료해 중복 발행을 재현한다.
- 같은 메시지를 동시에 두 번 전달해도 하나의 Worker만 작업을 점유하는지 검증한다.
- 결과 저장 직전·직후 Worker 종료를 각각 재현해 중복 결과가 없는지 검증한다.
- 재시도 한도 초과 시 작업 실패 상태와 DLQ 메시지를 함께 추적할 수 있는지 검증한다.

## 재검토 조건

- Change Data Capture가 polling Publisher보다 명확한 운영 이점을 제공한다.
- 다수 서비스·데이터베이스에 걸친 비즈니스 보상이 필요해 Saga가 요구된다.
- 처리량 증가로 Outbox polling과 테이블 정리가 DB 병목으로 측정된다.
- 관리형 워크플로 서비스가 장기 실행·단계 복구 요구에 더 적합해진다.


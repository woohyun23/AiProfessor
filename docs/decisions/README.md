# AiProfessor Architecture Decision Records

- 기준일: 2026-11-04
- 적용 범위: AiProfessor MVP
- 상태 의미: `제안`은 검토 중, `승인`은 구현 기준, `대체됨`은 새로운 ADR로 교체된 결정이다.

## ADR 목록

| ADR | 결정 | 상태 |
|---|---|---|
| [ADR-001](ADR-001-modular-monolith.md) | Spring Boot 모듈러 모놀리스와 분리 실행 가능한 API·Worker | 승인 |
| [ADR-002](ADR-002-sqs-pdf-processing.md) | PDF 비동기 처리에 AWS SQS Standard와 DLQ 사용 | 승인 |
| [ADR-003](ADR-003-postgresql-pgvector.md) | PostgreSQL과 pgvector를 관계형·벡터 저장소로 사용 | 승인 |
| [ADR-004](ADR-004-spring-ai-integration.md) | Spring AI 경계와 OpenAI 초기 제공자 사용 | 승인 |
| [ADR-005](ADR-005-low-cost-aws-deployment.md) | 단일 EC2 중심의 저비용 AWS MVP 배포 | 승인 |
| [ADR-006](ADR-006-outbox-idempotent-worker.md) | Transactional Outbox와 멱등적 Worker 적용 | 승인 |

## 운영 규칙

- 결정을 바꾸면 기존 ADR을 삭제하지 않고 `대체됨`으로 변경한 뒤 새로운 ADR을 추가한다.
- 아직 정하지 않은 버전, 모델명, 비용 상한과 튜닝값은 구현 중 암묵적으로 확정하지 않는다.
- 구현·부하 테스트·실배포로 검증되지 않은 내용은 `검증 예정`으로 표시한다.
- ADR은 선택 이유를 기록한다. 실제 설치법, 장애 복구 명령과 측정 결과는 각각 실행 문서, Runbook, 성능 보고서에 둔다.


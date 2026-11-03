# ADR-003: PostgreSQL과 pgvector 채택

- 상태: 승인
- 결정일: 2026-11-04
- 적용 범위: 서비스 데이터, PDF 청크와 임베딩 저장·검색
- 관련 문서: `docs/specifications/table-specification.md`

## 배경

AiProfessor는 사용자·문서·작업·문제·학습 기록 같은 관계형 데이터와 RAG 검색을 위한 청크·임베딩을 함께 저장해야 한다. 별도의 벡터 데이터베이스를 도입하면 초기부터 두 저장소의 권한, 삭제, 동기화, 백업과 비용을 관리해야 한다.

MVP는 한 사용자의 단일 PDF 학습 흐름부터 검증하며, 벡터 규모와 검색 부하는 아직 측정되지 않았다.

## 결정 기준

- 관계형 데이터의 트랜잭션과 제약조건을 사용할 수 있어야 한다.
- 사용자·문서 소유권 필터와 벡터 유사도 검색을 결합할 수 있어야 한다.
- 문서 삭제 시 관계형 데이터와 벡터를 일관되게 정리할 수 있어야 한다.
- 초기 AWS 비용과 운영 구성요소를 최소화해야 한다.
- 검색 성능을 데이터에 근거해 단계적으로 고도화할 수 있어야 한다.

## 검토한 선택지

| 선택지 | 장점 | 단점 | 판단 |
|---|---|---|---|
| PostgreSQL만 사용하고 벡터 외부 저장 | 관계형 구조가 단순함 | RAG 검색 구현·동기화가 복잡함 | 제외 |
| PostgreSQL + pgvector | 단일 DB 트랜잭션, SQL 필터와 벡터 검색 결합 | 대규모 전용 벡터 검색보다 확장 한계 | 선택 |
| 전용 Vector DB | 벡터 검색 기능과 독립 확장 | 별도 비용, 권한·삭제·동기화와 운영 증가 | MVP 이후 재검토 |
| OpenSearch Vector Engine | 검색·필터·하이브리드 기능 | 초기 인프라와 운영비가 큼 | MVP 이후 재검토 |

## 결정

관계형 데이터베이스로 PostgreSQL을 사용하고 `pgvector` 확장을 통해 `chunk_embeddings.embedding`을 저장한다. 문서 소유권과 메타데이터 필터를 적용한 뒤 코사인 거리 기반 Top-K 검색을 수행한다.

임베딩 차원은 ADR-004에서 선택할 모델에 맞춰 Flyway DDL 작성 전에 확정한다. 모델과 차원이 바뀌면 기존 벡터를 그대로 혼합하지 않고 모델 버전별 재임베딩·전환 계획을 세운다.

### 인덱싱 고도화 정책

인덱스는 미리 성능을 추정해 고정하지 않고 실제 조회 패턴과 데이터로 결정한다.

1. 초기 소규모 데이터에서는 exact search를 기준선으로 측정한다.
2. 일반 조회에는 다음 B-tree·부분 인덱스를 우선 적용한다.
   - `documents(user_id, created_at DESC) WHERE deleted_at IS NULL`
   - `documents(user_id, status) WHERE deleted_at IS NULL`
   - `document_chunks(document_id, page_start)`
   - `processing_jobs(status, next_retry_at)`
   - `outbox_events(status, next_attempt_at)`
3. 청크 규모별로 exact search, HNSW와 IVFFlat을 비교한다.
4. `EXPLAIN (ANALYZE, BUFFERS)`, 평균·p95 지연, Recall@K, 인덱스 크기, 생성 시간과 쓰기 비용을 기록한다.
5. 사용자·문서 필터가 approximate index의 Recall에 미치는 영향도 함께 검증한다.
6. 검증 결과를 별도 성능 보고서에 남기고 Flyway로 인덱스를 추가한다.

HNSW는 일반적으로 검색 성능과 speed-recall 균형이 좋지만 메모리와 생성 비용이 크다. IVFFlat은 생성이 빠르고 메모리가 적게 들 수 있지만 충분한 데이터와 `lists`·`probes` 튜닝이 필요하다. 따라서 둘 중 하나를 현재 ADR에서 확정하지 않는다.

## 결과

### 긍정적 결과

- 관계형 데이터와 벡터를 하나의 백업·권한·삭제 경계에서 관리한다.
- `user_id`, `document_id` 필터와 유사도 검색을 SQL로 결합할 수 있다.
- 별도 벡터 서비스 비용과 데이터 동기화 실패 지점을 줄인다.
- PostgreSQL 실행 계획과 벡터 인덱스를 함께 학습·측정할 수 있다.

### 감수할 결과

- 일반 트랜잭션과 벡터 검색이 동일 DB 자원을 공유한다.
- 데이터 증가 후 인덱스 메모리, VACUUM과 쓰기 성능을 관리해야 한다.
- 전용 벡터 DB의 분산 확장·고급 검색 기능을 바로 사용하지 못한다.

## 검증 계획

- 골든 질문을 이용해 검색 `Recall@K`와 근거 페이지 적중률을 측정한다.
- 데이터 규모별 검색 지연과 실행 계획을 기록한다.
- 문서 삭제 시 청크·임베딩이 함께 제거되는지 통합 테스트한다.
- 모델 버전이 다른 벡터가 같은 검색 공간에 섞이지 않는지 검증한다.

## 재검토 조건

- 벡터 검색이 PostgreSQL의 일반 트랜잭션 성능을 지속적으로 침해한다.
- 단일 인스턴스에서 목표 p95와 Recall@K를 동시에 충족하지 못한다.
- 대규모 분산 검색, 고급 하이브리드 검색 또는 독립 확장이 필요하다.
- 전용 벡터 저장소의 운영 비용보다 PostgreSQL 튜닝 비용이 커진다.

## 참고 자료

- [pgvector 공식 README](https://github.com/pgvector/pgvector/blob/master/README.md)


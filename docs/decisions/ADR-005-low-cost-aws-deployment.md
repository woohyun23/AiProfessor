# ADR-005: 단일 EC2 중심의 저비용 AWS MVP 배포

- 상태: 승인
- 결정일: 2026-11-04
- 적용 범위: 최초 AWS 실배포
- 관련 ADR: ADR-001, ADR-002, ADR-003

## 배경

MVP는 실제 AWS 환경에 배포하되 초기 운영비를 최소화해야 한다. 아직 트래픽과 벡터 규모가 측정되지 않았으므로 RDS, ECS/EKS, 다중 AZ 애플리케이션과 같은 상시 관리형 구성을 먼저 도입하면 기능 검증보다 인프라 비용과 운영 범위가 커진다.

동시에 PDF 원본과 작업 대기열은 단일 EC2 장애로부터 분리해야 하며, DB 손실에 대비한 백업과 복구 절차가 필요하다.

## 결정 기준

- 실제 AWS 배포와 HTTPS 접근이 가능해야 한다.
- 초기 고정비와 운영 구성요소를 최소화해야 한다.
- API와 Worker를 프로세스 수준에서 분리해야 한다.
- PDF 원본과 메시지를 EC2 로컬 디스크에만 의존하지 않아야 한다.
- 장애 시 복구 가능하며, 확장 시 교체 지점이 명확해야 한다.

## 검토한 선택지

| 선택지 | 장점 | 단점 | 판단 |
|---|---|---|---|
| 단일 EC2 + Docker Compose | 저비용, 구조 이해와 배포가 단순 | 단일 장애 지점, 수동 운영 증가 | 선택 |
| EC2 + RDS | DB 관리·백업 부담 감소 | 초기 고정비 증가 | 사용량 증가 후 전환 |
| ECS/Fargate + RDS | 서비스별 배포·확장 용이 | 초기 비용·구성 복잡도 증가 | MVP 이후 재검토 |
| EKS | 높은 확장성과 표준 오케스트레이션 | 현재 규모에 과도한 비용과 운영 | 제외 |

## 결정

최초 운영 토폴로지는 다음과 같다.

```text
Internet
  → Nginx / HTTPS
      ├─ React 정적 파일
      └─ Spring Boot API container

Spring Boot Worker container
  ├─ Amazon SQS Standard / DLQ
  ├─ Amazon S3 PDF objects
  └─ OpenAI API

PostgreSQL + pgvector container
  └─ encrypted EBS volume

S3 backup prefix
  └─ periodic pg_dump backup
```

Nginx, API, Worker와 PostgreSQL+pgvector는 하나의 EC2에서 Docker Compose로 실행한다. API와 Worker는 같은 코드베이스지만 별도 컨테이너·프로필·자원 제한을 사용한다. PDF 원본은 S3에 저장하고 SQS는 AWS 관리형 서비스를 사용한다.

### 운영 안전장치

- PostgreSQL 데이터는 컨테이너 writable layer가 아닌 암호화된 EBS에 둔다.
- DB 포트는 인터넷에 공개하지 않고 애플리케이션 내부 네트워크에서만 접근한다.
- S3 객체는 비공개로 유지하고 애플리케이션 권한과 제한된 presigned URL로 접근한다.
- EC2 Instance Role로 S3·SQS 최소 권한을 부여하고 장기 AWS 키를 이미지에 넣지 않는다.
- `pg_dump`를 주기적으로 S3에 보관하며 백업 생성뿐 아니라 복구 훈련까지 수행한다.
- 컨테이너 재시작 정책, 디스크·메모리·오류율·DLQ 알림을 설정한다.
- 배포, 롤백, DB 복구와 DLQ 대응 절차를 Runbook으로 작성한다.

인스턴스 유형, EBS 크기, 백업 주기, 보존 기간과 월 비용 상한은 배포 전 AWS Pricing Calculator 및 부하 측정으로 확정한다. 현재 ADR에서는 금액을 근거 없이 고정하지 않는다.

## 결과

### 긍정적 결과

- 관리형 컴퓨팅·DB의 상시 비용 없이 AWS 실배포 경험을 확보한다.
- 컨테이너 단위로 API, Worker와 DB의 실행 책임을 구분한다.
- S3와 SQS를 통해 PDF 원본과 대기 작업을 EC2 장애로부터 분리한다.

### 감수할 결과

- EC2 장애 시 API, Worker와 DB가 함께 중단되는 단일 장애 영역이 남는다.
- OS, Docker, PostgreSQL 패치와 백업 복구를 직접 운영한다.
- API와 벡터 검색·Worker가 CPU와 메모리를 경쟁한다.
- 무중단 배포와 자동 수평 확장은 MVP 범위에 포함되지 않는다.

## 검증 계획

- 새 EC2 또는 빈 볼륨 환경에서 문서화된 절차로 서비스를 복구한다.
- 최근 `pg_dump`로 PostgreSQL과 pgvector 데이터를 복원해 핵심 조회를 검증한다.
- API·Worker 재시작 중 SQS 메시지가 보존되고 다시 처리되는지 확인한다.
- 부하 테스트에서 API, Worker, PostgreSQL의 CPU·메모리·디스크 사용량을 각각 측정한다.
- 월별 EC2, EBS, S3, SQS와 AI 비용을 기록한다.

## 재검토 조건

- EC2 또는 PostgreSQL 장애 허용 시간이 MVP 운영 목표를 넘는다.
- 백업·패치 운영 부담이 RDS 추가 비용보다 커진다.
- Worker 부하가 API 응답 시간에 지속적으로 영향을 준다.
- 수평 확장, 무중단 배포 또는 다중 AZ가 서비스 요구사항이 된다.


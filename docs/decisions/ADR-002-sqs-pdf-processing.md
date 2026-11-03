# ADR-002: PDF 비동기 처리에 AWS SQS Standard 채택

- 상태: 승인
- 결정일: 2026-11-04
- 적용 범위: PDF 처리, 요약·문제 생성 등 장시간 작업
- 관련 ADR: ADR-005, ADR-006

## 배경

PDF의 텍스트 추출, 청킹, 임베딩과 AI 생성은 HTTP 요청 안에서 완료하기 어렵다. API는 작업을 접수하고 `202 Accepted`와 `jobId`를 반환하며, Worker가 백그라운드에서 처리해야 한다.

문서별 작업은 서로 독립적이고 전체 문서 사이의 처리 순서는 결과의 정확성에 영향을 주지 않는다. 필요한 기능은 복잡한 메시지 라우팅보다 내구성 있는 작업 대기열, 경쟁 소비자, 재시도와 실패 격리다.

## 결정 기준

- API와 장시간 Worker 작업을 분리해야 한다.
- 단일 EC2 장애 후에도 대기 중인 작업이 보존되어야 한다.
- 초기 저트래픽 환경에서 고정 운영비와 관리 작업을 줄여야 한다.
- 중복·순서 변경 전달을 애플리케이션에서 안전하게 처리할 수 있어야 한다.
- 반복 실패한 작업을 DLQ로 격리하고 다시 조사할 수 있어야 한다.

## 검토한 선택지

| 선택지 | 장점 | 단점 | 판단 |
|---|---|---|---|
| Spring `@Async` | 구현이 단순함 | 프로세스 종료 시 작업 유실, 독립 재시도·확장 한계 | 제외 |
| RabbitMQ 자체 운영 | 익숙한 AMQP, exchange 라우팅과 세밀한 제어 | 브로커 패치·모니터링·복구, 같은 EC2 사용 시 장애 영역 공유 | 제외 |
| Amazon MQ for RabbitMQ | RabbitMQ 기능과 관리형 운영 | MVP에 필요하지 않은 브로커 기능과 상시 비용 | 제외 |
| SQS FIFO | 메시지 그룹별 순서와 중복 제거 기능 | 문서 간 순서 보장이 불필요함 | 제외 |
| SQS Standard | 관리형 내구성, 사용량 기반, 높은 처리량 | at-least-once와 best-effort ordering을 고려해야 함 | 선택 |

## 결정

운영 환경의 비동기 작업 큐로 `AWS SQS Standard`를 사용하고 동일 유형의 DLQ를 연결한다.

이 결정의 핵심은 단순히 순서가 필요 없다는 점만이 아니다.

1. 요구사항이 topic/fanout 라우팅이 아닌 point-to-point 작업 분배다.
2. AWS가 브로커 운영과 내구성을 담당하므로 RabbitMQ 운영 작업을 제거할 수 있다.
3. 큐가 단일 EC2와 다른 장애 영역에 있어 서버 복구 후 작업을 계속할 수 있다.
4. 저트래픽 MVP에서 상시 브로커 용량보다 사용량 기반 서비스가 적합하다.
5. 문서별 독립성 때문에 Standard Queue의 순서 변경을 허용할 수 있다.

메시지에는 PDF 본문을 넣지 않고 다음 식별 정보만 넣는다.

```json
{
  "eventId": "uuid",
  "jobId": "uuid",
  "documentId": "uuid",
  "jobType": "PROCESS_DOCUMENT",
  "occurredAt": "ISO-8601 UTC"
}
```

### 전달·실패 정책

- Worker는 메시지를 처리한 뒤 DB 상태 커밋이 성공한 경우에만 메시지를 삭제한다.
- Visibility Timeout은 실제 처리 시간 측정값을 기준으로 설정한다.
- 장시간 작업은 heartbeat로 `ChangeMessageVisibility`를 호출해 가시성 시간을 연장한다.
- 일시적인 외부 API·네트워크 오류만 재시도하고 잘못된 PDF 같은 영구 오류는 즉시 실패 처리한다.
- 최대 수신 횟수를 초과한 메시지는 DLQ로 보내며, 원인을 수정한 뒤 명시적으로 redrive한다.
- 중복 전달은 ADR-006의 멱등적 상태 전이와 DB 제약으로 처리한다.

## 결과

### 긍정적 결과

- 브로커 설치, 패치, 클러스터와 백업 운영을 줄인다.
- API·Worker 중단 중에도 작업 대기열을 보존할 수 있다.
- Worker 수를 늘려 경쟁 소비 방식으로 확장할 수 있다.

### 감수할 결과

- 중복 및 순서 변경 전달을 전제로 Worker를 설계해야 한다.
- AWS 의존성이 생긴다.
- 로컬에서는 SQS 호환 환경 또는 별도 개발 Queue가 필요하다.
- Visibility Timeout과 DLQ redrive 운영 절차가 필요하다.

## 검증 계획

- 동일 메시지를 두 번 전달해도 청크·임베딩이 중복 생성되지 않는지 검증한다.
- Worker를 처리 중 종료한 뒤 Visibility Timeout 이후 작업이 재개되는지 검증한다.
- 최대 재시도 초과 메시지가 DLQ에 도착하는지 검증한다.
- 로컬 개발 방식은 LocalStack과 AWS 개발용 Queue를 비교한 뒤 실행 문서에서 확정한다.

## 재검토 조건

- 복잡한 topic/fanout 라우팅 또는 메시지 우선순위가 핵심 요구사항이 된다.
- AWS 외부·온프레미스에서도 동일한 브로커를 운영해야 한다.
- 한 문서의 하위 작업에 엄격한 순서가 필요하면 `documentId`를 메시지 그룹으로 하는 FIFO를 검토한다.
- 단일 작업이 SQS Visibility Timeout 한계를 넘는 장기 워크플로가 되면 작업 분할 또는 워크플로 서비스를 검토한다.

## 참고 자료

- [Amazon SQS Standard queues](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/standard-queues.html)
- [Amazon SQS visibility timeout](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-visibility-timeout.html)


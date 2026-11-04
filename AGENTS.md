# AiProfessor Repository Guide

## Project Overview

AiProfessor is a modular-monolith web application that processes uploaded PDF documents and provides RAG-based summaries, quizzes, and learning features.

The backend uses Java 21, Spring Boot, PostgreSQL, pgvector, Flyway, AWS S3, and AWS SQS.

## Repository Structure

- `backend/`: Spring Boot backend application
- `docs/requirements/`: MVP requirements
- `docs/specifications/`: API and database specifications
- `docs/decisions/`: Architecture Decision Records
- `docs/planning/`: Development plans and backlog
- `output/`: Generated artifacts

## Development Rules

- Implement features by domain rather than by technical layer at the repository root.
- Keep domain-specific code inside its domain module.
- Place only application-wide technical concerns under `support`.
- Do not introduce dependencies between domain modules without documenting the reason.
- Database schema changes must include a Flyway migration.
- Public API changes must remain consistent with the API specification.
- Use UUID values for externally exposed resource identifiers.
- Store and expose timestamps in UTC.

## Testing Rules

- Add tests for success and failure scenarios.
- Use PostgreSQL Testcontainers for persistence integration tests.
- Verify user ownership when accessing user-owned resources.
- Test duplicate requests and retries for asynchronous operations.
- Do not replace meaningful behavior tests with tests written only to increase coverage.

## Code Review Rules

- Write pull request review comments and review summaries in Korean.

### User data ownership

- User-owned resources must be queried and modified with the authenticated user ID.
- Return `404 Not Found` when a user requests another user's resource.
- Do not expose whether another user's resource exists.

### API contracts

- Follow the documented common success and error response formats.
- Do not expose passwords, tokens, object-storage keys, stack traces, or internal exception messages.
- Keep error codes consistent with the `DOMAIN_0000` format.

### Persistence

- Include a Flyway migration for every database schema change.
- Avoid unbounded queries for list APIs.
- Check whether new query patterns require indexes, but do not add indexes without a query-based reason.

### Asynchronous processing

- SQS consumers must tolerate duplicate delivery.
- Processing jobs must preserve retry count and failure information.
- Reprocessing the same job must not create duplicate results.
- Do not treat message acknowledgment as proof that the database transaction committed.

### RAG and AI

- Keep document and user boundaries in every retrieval query.
- Preserve document, page, and chunk identifiers for generated citations.
- Do not return generated claims without supporting document evidence.
- Record model and prompt versions for generated results.

## Pull Request Rules

- Link every feature PR to its GitHub Issue.
- Include the implemented scope, excluded scope, and verification evidence.
- Keep PRs small enough to review as one logical change.
- Do not merge when required CI checks fail.

# 전체 서비스 통합 실행

팀원의 Docker full profile/OpenRouter 코드와 제품 변경 알림·비동기 검사·리포트·여러 검사 접수 백엔드를 함께 실행한다. 루트 `.env`에 LLM_PROVIDER, 해당 공급자의 키, GEMINI_MODEL, JWT_SECRET을 설정하고 다음을 실행한다.

```sh
docker compose --profile full up -d --build
```

OpenRouter는 `LLM_PROVIDER=openrouter`, `OPENROUTER_API_KEY`를 사용한다. 모델 이름은 기존처럼 google/ 접두어 없이 지정하며 어댑터가 접두어를 붙인다. 재확인 모델 기본값은 팀원 코드의 gemini-3.6-flash를 유지한다. 실제 검사 한 건에도 판정·재확인·정보 대조 단계에 따라 여러 LLM 호출이 발생한다.

## DB 준비

- 새 DB 볼륨에서는 `deploy/init-db.sh`가 배포 migration runner를 실행해 번호 있는 backend SQL 전체 및 사례 테이블과 migration 이력을 만든다. 이어서 기존 제품·사례 seed를 적재한다.
- DB healthcheck는 TCP 접속을 확인한다. 초기 적재 중 임시 Unix socket 서버를 준비 완료로 오인하지 않는다.
- full profile의 migrate 서비스가 migration을 재확인하고 성공한 뒤 백엔드를 시작한다. judge 준비 상태도 확인한다. 프런트는 백엔드 준비 후 실행한다.
- 이력 없는 기존 수동 관리 DB나 수정된 migration은 자동 변환하지 않고 중단한다. DB를 지우거나 이력을 임의로 넣지 말고 별도 사본을 백업·검증한 뒤 전환해야 한다. `deploy/README.md` 참고.
- 재시작만으로 제품·사례 seed를 다시 적재하지 않는다. 백엔드가 자동으로 식약처 데이터를 수집하거나 외부 이메일을 보내지 않도록 full 구성에서는 두 기능을 끈다. 실제 운영 설정은 별도 deploy/compose.yml에서 한다.

## 판정·프록시

백엔드 POST는 jobId를 반환한다. 기존 worker가 대기열에서 처리하며 조회로 PENDING/RUNNING/DONE/FAILED/TIMEOUT을 확인한다. Judge 응답 대기는 180초이며, 이는 서비스 전체의 모든 내부 호출이 반드시 그 시간 안에 끝난다는 보장이 아니다. 화면 조회가 종료되거나 응답이 불확실해도 자동 재접수하지 않는다.

Nginx는 Docker DNS로 backend 주소를 갱신하므로 컨테이너 재생성 후 이전 IP에 고정되지 않는다. /api 접두어와 쿼리 문자열은 유지한다. 별도 배포 Compose의 judge에도 LLM_PROVIDER/OPENROUTER_API_KEY/재확인 모델을 전달한다. 임베딩 모델은 이미지에 포함되므로 빈 모델 캐시 볼륨으로 이미지 내용을 가리지 않는다.

루트 구성의 `judge_logs` 볼륨에는 `llm-usage.jsonl`로 모델·시간·입출력 토큰·공급자가 반환한 비용만 기록한다. 광고 원문·API 키는 이 사용량 기록에 포함하지 않는다. 장기 운영의 보관 정책이나 비용 상한 기능은 별도이며, 이 기록이 모든 실패·재시도 비용을 완전히 집계한다는 보장은 없다.

## 로컬과 배포

full profile은 로컬 HTTP 시연용으로 refresh cookie secure=false를 사용한다. 공개 운영은 HTTPS와 배포용 설정을 사용한다. 키는 .env에만 저장하고 커밋하지 않는다. 검수 기록·리포트는 기존 소유권 확인을 유지한다.

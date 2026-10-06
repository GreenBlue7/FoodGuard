## Git 워크플로우

### 1 브랜치 전략

| 브랜치                           | 용도                               |
| -------------------------------- | ---------------------------------- |
| `main`                           | 최종 발표 배포용                   |
| `develop`                        | 메인 브랜치 - 모든 PR의 merge 대상 |
| `feature/<topic>`                | 기능 개발                          |
| `chore/<topic>`, `infra/<topic>` | 빌드/인프라/설정 변경              |
| `fix/<topic>`                    | 버그 수정                          |

### 2 작업 원칙

```bash
# 로컬 develop 브랜치로 이동
git checkout develop
# origin/develop 브랜치 최신 데이터 fetch + rebase
git pull --rebase origin develop
# 최신 develop 상태에서 분기하여 작업
git checkout -b feature/내가할일

# 작업 완료 후: origin/develop이 앞으로 이동했을 수 있으므로
git fetch origin # origin/develop 최신화
git rebase origin/develop # 내 커밋들을 최신 develop 위로 재배치
# Conflict 발생 시: 충돌 파일 수정 --> git add <파일> --> git rebase --continue

# --force-with-lease: 마지막 fetch후 다른 사람이 같은 브랜치에 push했을 경우 강제 push거부
git push -u --force-with-lease origin feature/내가할일
# 이후 동일 브랜치 재push 시: git push --force-with-lease

# GitHub에서 PR 생성 → develop으로
```

# Kubernetes 배포

## 순서

```bash
# 1. Namespace
kubectl apply -f 00-namespace.yaml

# 2. Secrets (값은 직접 채우기)
kubectl apply -f 10-secrets.yaml
kubectl -n backtesting edit secret backtesting-secrets
# 또는 파일 기반:
kubectl -n backtesting create secret generic backtesting-secrets --from-env-file=.env --dry-run=client -o yaml | kubectl apply -f -

# 3. 인프라 (Redis, Kafka)
kubectl apply -f 20-redis.yaml
kubectl apply -f 30-kafka.yaml

# 4. 앱
kubectl apply -f 40-backend.yaml
kubectl apply -f 50-frontend.yaml

# 5. (선택) Ingress
kubectl apply -f 60-ingress.yaml
```

## 이미지 빌드 + 레지스트리 푸시

```bash
# Backend
docker build -t backtesting-tool-backend:latest backend/
docker tag backtesting-tool-backend:latest registry.example.com/backtesting-tool-backend:1.0.0
docker push registry.example.com/backtesting-tool-backend:1.0.0

# Frontend
docker build -t backtesting-tool-frontend:latest frontend/
docker tag backtesting-tool-frontend:latest registry.example.com/backtesting-tool-frontend:1.0.0
docker push registry.example.com/backtesting-tool-frontend:1.0.0
```

`k8s/40-backend.yaml` `k8s/50-frontend.yaml`의 `image:` 필드를 레지스트리 주소로 수정.

## 운영 고려사항

1. **Kafka 복제**: 현재 replicas=1 → 운영은 3. StatefulSet의 replicas 증가 + `KAFKA_CFG_CONTROLLER_QUORUM_VOTERS` 조정.
2. **H2 → Postgres 전환 권장**: 운영에서는 `application.yml`의 datasource를 Postgres로. `CloudNativePG` 오퍼레이터 활용.
3. **Backend 다중 복제**: 현재 single-instance 제약 있음 (H2 파일 DB, KIS WS 단일 연결). Postgres 전환 + WS leader election 구현 후 가능.
4. **모니터링**: Prometheus ServiceMonitor + Actuator `/actuator/prometheus` 엔드포인트. Grafana 대시보드.
5. **로그**: Fluent Bit → Loki/ES.
6. **Network Policy**: 백엔드 Pod가 Redis/Kafka만 접근하도록 제한.
7. **외부 접근**: Ingress HTTPS 필수. cert-manager + Let's Encrypt.

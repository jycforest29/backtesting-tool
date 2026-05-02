# ========== Build stage ==========
FROM node:20-alpine AS build
WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

# ========== Runtime stage — nginx static serving ==========
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
# templates/ 에 두면 공식 이미지 entrypoint 가 시작 시 envsubst 실행 후 conf.d/ 로 출력.
# 컨테이너 환경변수의 식별자만 화이트리스트로 치환되므로 nginx 런타임 변수($host 등)는 안전.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template

EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost/ || exit 1

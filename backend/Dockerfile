# ========== Build stage ==========
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app

# Maven wrapper 미사용이라 maven 이미지로 빌드 단계 분리하는 것도 가능.
# 여기서는 사전에 mvn package 한 jar를 복사하는 방식과 소스 전체 빌드 모두 지원.
RUN apk add --no-cache maven

COPY pom.xml .
# 의존성 레이어 캐싱: 소스 변경 시에도 dependency 재다운로드 방지
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package
RUN cp target/*.jar /app/app.jar

# ========== Runtime stage ==========
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 비-root 유저로 실행 (보안)
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /app/app.jar /app/app.jar

# H2 파일 저장용 볼륨 마운트 지점
VOLUME ["/data"]
ENV DB_PATH=/data/backtesting-tool-db

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
ENV SPRING_PROFILES_ACTIVE=docker

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

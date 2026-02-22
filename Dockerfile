# =============================================================
# Runtime image — expects the JAR to be pre-built locally:
#   ./gradlew bootJar -x test
# Then:
#   docker compose up --build
# =============================================================
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

WORKDIR /app
COPY build/libs/*-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseSerialGC", \
  "-Xms128m", \
  "-Xmx512m", \
  "-XX:MaxMetaspaceSize=192m", \
  "-XX:+UseContainerSupport", \
  "-Dfile.encoding=UTF-8", \
  "-Duser.timezone=UTC", \
  "-jar", "app.jar"]

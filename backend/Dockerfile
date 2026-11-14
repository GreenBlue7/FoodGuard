FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN sed -i 's/\r$//' gradlew && sh ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system foodguard && useradd --system --gid foodguard foodguard
COPY --from=build --chown=foodguard:foodguard /src/build/libs/*-SNAPSHOT.jar /app/app.jar
USER foodguard
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

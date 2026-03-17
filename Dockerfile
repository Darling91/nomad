# Этап 1: Сборка с помощью Gradle
FROM eclipse-temurin:25-jdk-alpine AS build

WORKDIR /app

# Копируем все файлы Gradle
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

RUN chmod +x gradlew

# Скачиваем зависимости
RUN ./gradlew dependencies --no-daemon || true

# Копируем исходный код
COPY src src

# Копируем сгенерированные jOOQ классы (исправленный путь)
COPY build/generated/sources/jooq/main build/generated/sources/jooq/main

# Собираем приложение
RUN ./gradlew bootJar -x test -x generateJooq --no-daemon

# Этап 2: Финальный образ с оптимизацией памяти
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080 10000

ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-Xmx384m", \
    "-Xms256m", \
    "-XX:MaxMetaspaceSize=128m", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-jar", "app.jar"]

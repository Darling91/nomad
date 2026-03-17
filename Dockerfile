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

# Копируем сгенерированные jOOQ классы
COPY build/generated/sources/jooq build/generated/sources/jooq

# Собираем приложение
RUN ./gradlew bootJar -x test -x generateJooq --no-daemon

# Этап 2: Финальный образ (ваш рабочий вариант)
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

# Создаем пользователя и папку для логов
RUN addgroup -S appgroup && \
    adduser -S appuser -G appgroup && \
    mkdir -p /app/logs && \
    chown -R appuser:appgroup /app

USER appuser

# Порт приложения
EXPOSE 8080

# Запуск с уменьшенным потреблением памяти (384MB -> 256MB)
ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-Xmx256m", \
    "-Xms128m", \
    "-XX:MaxMetaspaceSize=64m", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-jar", "app.jar"]

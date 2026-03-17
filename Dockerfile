# Этап 1: Сборка с помощью Gradle
FROM eclipse-temurin:25-jdk-alpine AS build

WORKDIR /app

# Копируем только файлы для загрузки зависимостей (для кэширования)
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

RUN chmod +x gradlew

# Загружаем зависимости (кэшируется, если build.gradle не менялся)
RUN ./gradlew dependencies --no-daemon || true

# Копируем исходный код и сгенерированные классы
COPY src src
COPY build/generated/sources/jooq build/generated/sources/jooq

# Собираем приложение
RUN ./gradlew bootJar -x test -x generateJooq --no-daemon

# Этап 2: Минимальный образ для запуска
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

# Копируем только JAR из этапа сборки
COPY --from=build /app/build/libs/*.jar app.jar

# Порт приложения
EXPOSE 8080

# Пользователь не-root для безопасности
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Запуск с оптимизациями памяти
ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-Xmx384m", \
    "-Xms256m", \
    "-XX:MaxMetaspaceSize=128m", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-jar", "app.jar"]

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

# Копируем сгенерированные jOOQ классы (они уже есть в вашем проекте)
COPY build/generated/sources/jooq build/generated/sources/jooq

# Собираем приложение (jOOQ генерация уже не нужна)
RUN ./gradlew bootJar -x test -x generateJooq --no-daemon

# Этап 2: Финальный образ
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseZGC", "-Xmx512m", "-Xms256m", "-jar", "app.jar"]
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

# Этап 2: Финальный образ с оптимизацией памяти
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Копируем собранный jar
COPY --from=build /app/build/libs/*.jar app.jar

# Порт приложения (Render автоматически устанавливает PORT=10000)
EXPOSE 8080 10000

# Оптимизированные настройки памяти для Render (512MB лимит)
# -Xmx384m: оставляем ~128MB для Metaspace и системы
# -XX:MaxMetaspaceSize=128m: ограничиваем Metaspace
# -XX:+UseZGC: используем ZGC для лучшей производительности
# -XX:+ExitOnOutOfMemoryError: перезапуск при OOM
ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-Xmx384m", \
    "-Xms256m", \
    "-XX:MaxMetaspaceSize=128m", \
    "-XX:CompressedClassSpaceSize=32m", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-XX:+HeapDumpOnOutOfMemoryError", \
    "-XX:HeapDumpPath=/tmp/heapdump.hprof", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]

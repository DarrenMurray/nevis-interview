# syntax=docker/dockerfile:1

# ---------- build ----------
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /build

# Descriptors first: dependency resolution gets its own cached layer, re-run only when
# pom.xml or the wrapper changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline

COPY src/ src/
RUN ./mvnw -B clean package -DskipTests

# Split the fat jar so pulls transfer only what changed.
# --launcher is required: without it spring-boot-loader/ is empty and the JarLauncher
# entrypoint below cannot resolve.
RUN java -Djarmode=tools -jar target/search-api-*.jar extract --layers --launcher --destination extracted

# ---------- runtime ----------
FROM eclipse-temurin:25-jre-noble AS runtime
WORKDIR /app

# Not guaranteed in the JRE base image; the compose healthcheck needs it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Nothing here needs root.
RUN groupadd --system --gid 1001 app \
    && useradd --system --uid 1001 --gid app --no-create-home app

# One layer per COPY, least- to most-frequently changed. Flattened into /app so the JVM's
# default classpath (the working directory) resolves the launcher.
COPY --from=build --chown=app:app /build/extracted/dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/application/ ./

USER app
EXPOSE 8080

# JVM flags go in JAVA_TOOL_OPTIONS, which the JVM reads itself — exec form, no shell, so
# signals are handled correctly.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

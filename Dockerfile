# syntax=docker/dockerfile:1

# ---------- build ----------
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /build

# Copy only the build descriptors first. Dependency resolution is the slow step, so
# giving it its own layer means it is re-run only when pom.xml or the wrapper changes,
# not on every source edit.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests

# Split the fat jar into layers that change at different rates (dependencies rarely,
# application code constantly) so image pulls only transfer what actually changed.
RUN java -Djarmode=tools -jar target/search-api-*.jar extract --layers --destination extracted

# ---------- runtime ----------
FROM eclipse-temurin:25-jre-noble AS runtime
WORKDIR /app

# Run unprivileged: nothing here needs root.
RUN groupadd --system --gid 1001 app \
    && useradd --system --uid 1001 --gid app --no-create-home app

# Each COPY is its own image layer, ordered least- to most-frequently changed.
# The layer directories are flattened into /app so that the JVM's default classpath
# (the working directory) resolves the launcher and the application.
COPY --from=build --chown=app:app /build/extracted/dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/application/ ./

USER app
EXPOSE 8080

# Pass JVM flags at runtime via JAVA_TOOL_OPTIONS, which the JVM reads on its own —
# so this stays an exec-form entrypoint with no shell wrapper and correct signal handling.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

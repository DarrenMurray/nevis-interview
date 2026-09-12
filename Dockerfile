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

# Embedding model weights, fetched at build time so the running container never reaches the
# network for them. Without this the first embed downloads ~90MB, which on Cloud Run with
# min-instances 0 happens on every cold start.
ARG MINILM=https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /build/onnx \
    && curl -fsSL -o /build/onnx/model.onnx "${MINILM}/onnx/model.onnx" \
    && curl -fsSL -o /build/onnx/tokenizer.json "${MINILM}/tokenizer.json" \
    && test -s /build/onnx/model.onnx && test -s /build/onnx/tokenizer.json

# Load the model once so DJL downloads its native libraries into a cache we can bake in.
# Without this the first embed at runtime fetches ~200MB of natives.
ENV DJL_CACHE_DIR=/build/djl
RUN SPRING_AI_EMBEDDING_TRANSFORMER_ONNX_MODEL_URI=file:/build/onnx/model.onnx \
    SPRING_AI_EMBEDDING_TRANSFORMER_TOKENIZER_URI=file:/build/onnx/tokenizer.json \
    java -Dloader.main=com.nevis.search.search.EmbeddingWarmup \
         -cp "$(ls target/search-api-*.jar)" \
         org.springframework.boot.loader.launch.PropertiesLauncher \
    && du -sh /build/djl

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

COPY --from=build --chown=app:app /build/onnx/ /app/onnx/
COPY --from=build --chown=app:app /build/djl/ /app/.djl/

# Point Spring AI at the baked weights instead of its download-and-cache default.
ENV SPRING_AI_EMBEDDING_TRANSFORMER_ONNX_MODEL_URI=file:/app/onnx/model.onnx \
    SPRING_AI_EMBEDDING_TRANSFORMER_TOKENIZER_URI=file:/app/onnx/tokenizer.json \
    DJL_CACHE_DIR=/app/.djl

USER app
EXPOSE 8080

# JVM flags go in JAVA_TOOL_OPTIONS, which the JVM reads itself - exec form, no shell, so
# signals are handled correctly.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

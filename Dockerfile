# Builds the InterMine Registry and runs it:
#   docker build -t intermine-registry .
#   docker run -p 3000:3000 -v registry-cache:/cache intermine-registry
# Configure with environment variables (README.md, Configuration). The base
# images are pinned by digest (the multi-arch index digests BlueGenes' Dockerfile
# uses, 2026-11-15); refresh them deliberately.

# Build stage: Clojure CLI on JDK 21.
# clojure:temurin-21-tools-deps-bookworm-slim
FROM clojure@sha256:c9e8c8ddfa14f3d49ef31db775bf77368ee6be658f82865d527523045d20a26d AS build
WORKDIR /build
COPY deps.edn ./
# Dependencies in their own layer: the runtime classpath and the :build tool alias.
RUN clojure -P && clojure -P -T:build
COPY build.clj ./
COPY src src
COPY resources resources
RUN clojure -T:build uber

# Runtime stage: the uberjar on a JRE, as an unprivileged user.
# eclipse-temurin:21-jre
FROM eclipse-temurin@sha256:18bb4a91a3dea3f03ee31d3755af34dfb40f904fc77ab56cdc2f092e32fff9c2
RUN useradd --system --uid 10001 --home-dir /nonexistent --shell /usr/sbin/nologin registry \
 && mkdir /cache && chown registry /cache
COPY --from=build /build/target/intermine-registry.jar /intermine-registry.jar
USER registry
ENV PORT=3000 \
    REGISTRY_CACHE_DIR=/cache
VOLUME /cache
EXPOSE 3000
# curl is part of the eclipse-temurin images
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS "http://localhost:${PORT}/health" > /dev/null || exit 1
CMD ["java", "-XX:MaxRAMPercentage=75", "-jar", "/intermine-registry.jar"]

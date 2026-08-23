# ---- build stage -------------------------------------------------------------
# The JDK and Maven live only here; the final image never sees them.
FROM eclipse-temurin:26-jdk AS build
WORKDIR /build

# dependencies first: this layer is cached and only re-runs when pom.xml changes,
# so an ordinary code edit does not re-download the internet
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests

# ---- run stage ---------------------------------------------------------------
FROM eclipse-temurin:26-jre
WORKDIR /app

# curl is only here for HEALTHCHECK; the JRE image does not ship it
RUN apt-get update  && apt-get install -y --no-install-recommends curl  && rm -rf /var/lib/apt/lists/*

# never run as root: a container escape should not land on a privileged user
RUN groupadd --system wallet && useradd --system --gid wallet wallet
COPY --from=build --chown=wallet:wallet /build/target/*.jar app.jar
USER wallet

EXPOSE 8080

# the JVM reads the container's memory limit rather than the host's
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
    CMD ["sh", "-c", "curl -fsS http://localhost:8080/actuator/health || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

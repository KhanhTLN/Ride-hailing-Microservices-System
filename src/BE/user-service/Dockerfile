# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# Maven loads the reactor POMs before selecting the requested module.
COPY pom.xml ./
COPY common/pom.xml common/
COPY user-service/pom.xml user-service/
COPY driver-service/pom.xml driver-service/
COPY pricing-service/pom.xml pricing-service/
COPY trip-service/pom.xml trip-service/
COPY common/src common/src
COPY user-service/src user-service/src

RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -ntp -pl user-service -am -Dtest=JwtTokenProviderTest,CorrelationFilterTest -Dsurefire.failIfNoSpecifiedTests=false package \
    && cp user-service/target/user-service-*.jar /app.jar

FROM eclipse-temurin:25-jdk-alpine-3.23 AS java-runtime
# Keep the modules needed by Spring, Hibernate, the PostgreSQL driver and gRPC.
RUN jlink \
    --add-modules java.compiler,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.security.jgss,java.sql,jdk.crypto.ec,jdk.management,jdk.unsupported \
    --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
    --output /opt/java-runtime

FROM alpine:3.23
RUN apk add --no-cache ca-certificates libstdc++ zlib
COPY --from=java-runtime /opt/java-runtime /opt/java-runtime
WORKDIR /app
COPY --from=build --chmod=444 /app.jar /app/app.jar
USER 10001:10001
EXPOSE 8082 9090
ENTRYPOINT ["/opt/java-runtime/bin/java", "-jar", "/app/app.jar"]

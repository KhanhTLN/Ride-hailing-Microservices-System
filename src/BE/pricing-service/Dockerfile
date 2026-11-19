# =========================================================================
# STAGE 1: Build Application
# =========================================================================
FROM eclipse-temurin:22-jdk AS builder

WORKDIR /build

# 1. Sao chép Maven Wrapper & toàn bộ POM files để khai báo đầy đủ multi-module
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY common/pom.xml common/
COPY pricing-service/pom.xml pricing-service/
COPY trip-service/pom.xml trip-service/
COPY user-service/pom.xml user-service/
COPY driver-service/pom.xml driver-service/

# Cấp quyền thực thi và chuyển LF cho mvnw, tải trước các dependency
RUN sed -i 's/\r$//' ./mvnw && chmod +x ./mvnw && \
    ./mvnw dependency:go-offline -B -pl :pricing-service -am || true

# 2. Sao chép mã nguồn của common và pricing-service
COPY common/src common/src
COPY pricing-service/src pricing-service/src

# 3. Biên dịch và đóng gói chỉ pricing-service và common (bỏ qua unit tests)
RUN ./mvnw clean package -B -pl :pricing-service -am -DskipTests

# 4. Trích xuất thin jar và dependencies bằng Spring Boot tools
WORKDIR /build/pricing-service
RUN java -Djarmode=tools -jar target/pricing-service-0.0.1-SNAPSHOT.jar extract --destination /build/extracted

# =========================================================================
# STAGE 2: Lightweight Runtime Image
# =========================================================================
FROM eclipse-temurin:22-jre-alpine

WORKDIR /app

# Tạo non-root user để tăng cường bảo mật container
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Tách layer: Dependencies (thư viện bên thứ 3) ít thay đổi, đặt trước để tận dụng Docker Cache
COPY --from=builder --chown=appuser:appgroup /build/extracted/lib ./lib

# Layer ứng dụng (chỉ vài chục KB) đặt sau cùng, build lại cực nhanh khi sửa code
COPY --from=builder --chown=appuser:appgroup /build/extracted/pricing-service-0.0.1-SNAPSHOT.jar app.jar

USER appuser

# Cổng REST HTTP (8083) và gRPC (9093)
EXPOSE 8083 9093

# Tối ưu RAM cho container: tự động nhận diện giới hạn RAM của Docker container
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]

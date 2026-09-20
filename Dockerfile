# Giai đoạn build cần JDK để biên dịch Java và đóng gói ứng dụng.
FROM eclipse-temurin:17-jdk AS build
# Thư mục làm việc bên trong container.
WORKDIR /app

# Chép Maven Wrapper và cấu hình trước để Docker có thể dùng lại cache dependency.
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Cho phép chạy Maven Wrapper và tải trước các thư viện cần thiết.
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Chép mã nguồn rồi tạo JAR; bước này bỏ qua test.
COPY src src
RUN ./mvnw clean package -DskipTests

# Giai đoạn chạy chỉ cần JRE nên image cuối nhỏ hơn image build.
FROM eclipse-temurin:17-jre
# Thư mục ứng dụng bên trong container.
WORKDIR /app

# Lấy JAR đã build ở giai đoạn trước và đặt tên cố định.
COPY --from=build /app/target/*.jar app.jar

# Giới hạn heap theo bộ nhớ container và đặt cổng mặc định.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENV PORT=10000

# Khai báo cổng ứng dụng dùng trong container.
EXPOSE 10000

# Chạy ứng dụng; exec giúp tiến trình Java nhận tín hiệu dừng trực tiếp.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=${PORT:-10000} -Dserver.address=0.0.0.0 -jar app.jar"]

# syntax=docker/dockerfile:1
# 注意：基础镜像使用 DaoCloud 加速源（与你们 Dify 部署同一镜像源）。
# 若网络能直连 Docker Hub，去掉 docker.m.daocloud.io/library/ 前缀即可。

# 构建阶段
FROM docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# 运行阶段
FROM docker.m.daocloud.io/library/eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/dify-rag-lab-0.0.1.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM node:20 AS frontend
WORKDIR /app/frontend
ARG VITE_CLERK_PUBLISHABLE_KEY
ENV VITE_CLERK_PUBLISHABLE_KEY=${VITE_CLERK_PUBLISHABLE_KEY}
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN corepack enable && pnpm install
COPY frontend .
RUN pnpm run build

FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /app
COPY backend/pom.xml .
RUN mvn dependency:go-offline

COPY backend .
COPY --from=frontend /app/frontend/dist /app/src/main/resources/static
RUN mvn package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

# Install FFmpeg for video processing and curl for container health checks
RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg curl && \
    rm -rf /var/lib/apt/lists/*

COPY --from=backend-build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java","-jar","app.jar"]

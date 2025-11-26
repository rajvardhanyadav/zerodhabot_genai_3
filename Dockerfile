FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
COPY target/*.jar zerodhabot_genai_3-4.1.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/zerodhabot_genai_3-4.1.jar"]
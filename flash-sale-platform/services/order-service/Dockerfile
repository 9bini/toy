FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

COPY build/libs/*.jar app.jar
RUN chown app:app app.jar

USER app
EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]

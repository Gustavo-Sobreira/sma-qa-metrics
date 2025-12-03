FROM maven:3.9.6-eclipse-temurin-17 as build

WORKDIR /app

COPY pom.xml .
COPY libs ./libs
COPY src ./src

# Compila com jade.jar no build
RUN mvn -q -e -DskipTests package

FROM eclipse-temurin:17

WORKDIR /app

COPY --from=build /app/target/*.jar /app/app.jar
COPY --from=build /app/libs/jade.jar /app/libs/jade.jar

ENV CLASSPATH=/app/libs/jade.jar:/app/app.jar

CMD ["java", "-cp", "/app/libs/jade.jar:/app/app.jar", "br.uerj.multiagentes.MainContainer"]

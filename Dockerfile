FROM maven:3.9.4-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src
COPY JADE-bin-4.6.0/jade/lib/jade.jar /tmp/jade.jar

RUN mvn install:install-file \
    -Dfile=/tmp/jade.jar \
    -DgroupId=com.tilab.jade \
    -DartifactId=jade \
    -Dversion=4.6.0 \
    -Dpackaging=jar

RUN mvn -q -DskipTests package


FROM eclipse-temurin:21-jre

WORKDIR /app

# COPY --from=build /app/target/app.jar app.jar
COPY --from=build /app/target/app-jar-with-dependencies.jar app.jar
COPY --from=build /tmp/jade.jar jade.jar

CMD ["java", "-cp", "app.jar:jade.jar", "com.agents.MainContainer"]

FROM adoptopenjdk:15.0.2_7-jdk-hotspot
RUN jlink --compress=2 --no-header-files --no-man-pages \
      --add-modules java.base,java.logging,java.xml,jdk.unsupported,java.sql,java.naming,java.desktop,java.management,java.security.jgss,java.instrument \
      --output /usr/lib/jvm/spring-boot-runtime

FROM debian:buster-slim as builder
COPY --from=0 /usr/lib/jvm/spring-boot-runtime /usr/lib/jvm/spring-boot-runtime
WORKDIR /home/app
ARG JAR_FILE=build/libs/vsa2gpkg-*.jar
COPY ${JAR_FILE} /home/app/application.jar
RUN /usr/lib/jvm/spring-boot-runtime/bin/java -Djarmode=layertools -jar /home/app/application.jar extract

FROM debian:buster-slim
COPY --from=0 /usr/lib/jvm/spring-boot-runtime /usr/lib/jvm/spring-boot-runtime
EXPOSE 8080
WORKDIR /home/app
COPY --from=builder /home/app/dependencies/ ./
COPY --from=builder /home/app/spring-boot-loader/ ./
COPY --from=builder /home/app/snapshot-dependencies/ ./
RUN true 
COPY --from=builder /home/app/application/ ./
RUN chown -R 1001:0 /home/app && \
    chmod -R g=u /home/app
USER 1001
ENTRYPOINT ["/usr/lib/jvm/spring-boot-runtime/bin/java", "-XX:+UseParallelGC", "-XX:MaxRAMPercentage=80.0", "-XX:TieredStopAtLevel=1", "org.springframework.boot.loader.JarLauncher"]


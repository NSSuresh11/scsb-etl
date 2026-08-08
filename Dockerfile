FROM scsb-base as builder
WORKDIR application
ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} scsb-etl.jar
RUN java -Djarmode=layertools -jar scsb-etl.jar extract

FROM scsb-base

RUN apt-get update && \
    apt-get install -q -y zip
RUN apt-get -qq -y install curl tar
RUN curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
RUN unzip awscliv2.zip
RUN ./aws/install

WORKDIR application
COPY --from=builder application/dependencies/ ./
COPY --from=builder application/spring-boot-loader/ ./
COPY --from=builder application/snapshot-dependencies/ ./
COPY --from=builder application/scsb-etl.jar/ ./
ENTRYPOINT java -jar -Denvironment=$ENV scsb-etl.jar && bash

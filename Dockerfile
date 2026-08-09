FROM scsb-base AS builder
WORKDIR /application
ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} scsb-etl.jar
#RUN java -Djarmode=layertools -jar scsb-etl.jar extract
RUN java -Djarmode=tools -jar scsb-etl.jar extract --destination extracted --layers --launcher

FROM scsb-base

RUN apt-get update && \
    apt-get install -q -y zip
RUN apt-get -qq -y install curl tar
RUN curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
RUN unzip awscliv2.zip
RUN ./aws/install

WORKDIR application
COPY --from=builder /application/extracted/dependencies/ ./
COPY --from=builder /application/extracted/spring-boot-loader/ ./
COPY --from=builder /application/extracted/snapshot-dependencies/ ./
COPY --from=builder /application/extracted/scsb-etl.jar/ ./
ENTRYPOINT java -jar -Denvironment=$ENV scsb-etl.jar && bash

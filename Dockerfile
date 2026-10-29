# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e
FROM registry.k8s.io/kubectl:v1.37.1@sha256:b7cab618e281b1ee7484e7b706a96e2135fbb6e072c2a573a7dab4e87d7f2385 AS kubectl
FROM gcr.io/distroless/java25-debian13:nonroot@sha256:1faed3fc8dc16d47748e62167a7783d73299a277a4b695b90e8f530ea6a2c36f

ARG VERSION=1.0.0-rc1
ARG JAR_FILE=agentworks-server/target/agentworks-server-${VERSION}.jar
ARG REVISION=unknown
ARG SOURCE_DATE_EPOCH=315532800
ARG SOURCE=https://github.com/dvarahq/dvara-agentworks

LABEL org.opencontainers.image.title="Dvara AgentWorks Core" \
      org.opencontainers.image.description="Dvara AgentWorks modular-monolith server" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.source="${SOURCE}" \
      org.opencontainers.image.licenses="Apache-2.0"

WORKDIR /opt/agentworks
COPY --chown=65532:65532 ${JAR_FILE} /opt/agentworks/agentworks.jar
COPY --chown=65532:65532 deploy/oci/state/ /var/lib/agentworks/
COPY --from=kubectl /bin/kubectl /usr/local/bin/kubectl

USER 65532:65532
EXPOSE 8080
VOLUME ["/var/lib/agentworks"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/opt/agentworks/agentworks.jar"]

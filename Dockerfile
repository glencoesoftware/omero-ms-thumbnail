# Development Dockerfile for Microservices
# ----------------------------------------
# This dockerfile can be used to build
# a distribution which can then be run
# within a number of different Docker images.

# By default, building this dockerfile will use
# the IMAGE argument below for the runtime image:

ARG IMAGE=verx/vertx3

# To install the built distribution into other runtimes
# pass a build argument, e.g.:
#
#   docker build --build-arg IMAGE=openjdk:9 ...
#

# Similarly, the GRADLE_IMAGE argument can be overwritten
# but this is generally not needed.
ARG GRADLE_IMAGE=gradle:jdk-alpine

#
# Build phase: Use the gradle image for building.
#
FROM ${GRADLE_IMAGE} as gradle
RUN mkdir -p /src/target

## RUN apt-get update && \
##    apt-get install -y python-sphinx

RUN useradd -ms /bin/bash ms
COPY . /src
RUN chown -R ms /src
USER ms
WORKDIR /src
RUN git submodule update --init
RUN ./gradlew installDist

#
# Install phase: Copy the built distribution into a
# clean container to minimize size.
#
FROM ${IMAGE}
COPY --from=gradle /src/ /opt/build

ENV VERTICLE_NAME com.glencoesoftware
ENV VERTICLE_FILE target/
ENV VERTICLE_HOME /usr/verticles
ENV JAVA_OPTS "-Xmx1GB"

COPY $VERTICLE_FILE $VERTICLE_HOME/
EXPOSE 8080

RUN useradd -ms /bin/bash vertx
USER vertx

WORKDIR $VERTICLE_HOME
ENTRYPOINT ["sh", "-c"]
CMD ["exec vertx run $VERTICLE_NAME -cp $VERTCILE_HOME/*"]

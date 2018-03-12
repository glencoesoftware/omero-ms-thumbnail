# Development Dockerfile for Microservices
# ----------------------------------------
# This dockerfile can be used to build
# a distribution which can then be run
# within a number of different Docker images.

# By default, building this dockerfile will use
# the IMAGE argument below for the runtime image:

ARG IMAGE=vertx/vertx3

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
RUN mkdir -p ms

COPY build.gradle ms/
COPY src ms/src
WORKDIR ms
RUN gradle installDist


#
# Install phase: Copy the built distribution into a
# clean container to minimize size.
#
FROM ${IMAGE}
COPY --from=gradle /home/gradle/ms/build/install/ms /usr/verticles/ms

EXPOSE 8080

ENV JAVA_OPTS "-Xmx1G"
WORKDIR /usr/verticles/ms
ENTRYPOINT ["bash", "bin/ms"]

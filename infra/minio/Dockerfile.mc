# syntax=docker/dockerfile:1

ARG GO_IMAGE=golang:1.24.6-alpine3.22
ARG RUNTIME_IMAGE=alpine:3.22

FROM ${GO_IMAGE} AS build
ARG MC_TAG=RELEASE.2025-08-13T08-35-41Z
ARG MC_COMMIT=7394ce0dd2a80935aded936b09fa12cbb3cb8096

RUN apk add --no-cache bash curl git make perl
WORKDIR /src/mc
RUN git init \
    && git remote add origin https://github.com/minio/mc.git \
    && git fetch --depth=1 origin "refs/tags/${MC_TAG}:refs/tags/${MC_TAG}" \
    && git checkout --detach "${MC_TAG}" \
    && test "$(git rev-parse HEAD)" = "${MC_COMMIT}" \
    && test "$(git describe --tags --exact-match)" = "${MC_TAG}"
RUN MC_RELEASE=RELEASE make build \
    && ./mc --version | grep -F "${MC_TAG}"

FROM ${RUNTIME_IMAGE}
RUN apk add --no-cache ca-certificates
COPY --from=build /src/mc/mc /usr/bin/mc
COPY --from=build /src/mc/LICENSE /usr/share/licenses/minio-mc/LICENSE
ENTRYPOINT ["/usr/bin/mc"]

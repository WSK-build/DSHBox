# Dockerfile for reproducible Debian ARM64 Runtime rootfs.
#
# Build:
#   docker buildx build --platform linux/arm64 \
#     -f runtime-bundle/Dockerfile -t dshapp-rootfs:latest .
#
# Then export:
#   docker create --name dshapp-rootfs dshapp-rootfs:latest
#   docker export dshapp-rootfs | tar -x -C build/rootfs/trixie-arm64
#   docker rm dshapp-rootfs
#
# Or use runtime-bundle/build_rootfs.sh docker.

FROM --platform=linux/arm64 debian:trixie-slim

ARG DSH_VERSION=0.1.0-rc.6
ARG NODE_VERSION=22.17.0
ARG NODE_DISTRO=linux-arm64

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash ca-certificates curl wget openssl \
    coreutils util-linux procps findutils grep sed gawk diffutils \
    tar gzip bzip2 xz-utils zip unzip file less \
    git openssh-client rsync make gcc g++ cmake pkg-config \
    python3 python3-pip python3-venv \
    tzdata locales \
    && rm -rf /var/lib/apt/lists/*

# Node.js binary distribution pinned to a specific version.
RUN curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-${NODE_DISTRO}.tar.xz" \
    | tar -xJ -C /usr/local --strip-components=1

# pnpm via corepack (bundled with Node). Pin by packageManager after this step.
RUN corepack enable && corepack prepare pnpm@9.15.0 --activate

# Install DSH into a dedicated runtime directory. Pin the exact version; do not
# rely on latest dist-tags of @deepseek-ai/dsh-* subpackages.
RUN mkdir -p /opt/dshapp/runtime /opt/dshapp/workspace && \
    npm install --prefix /opt/dshapp/runtime "npm:@deepseek-ai/dsh@${DSH_VERSION}" && \
    ln -sf /opt/dshapp/runtime/node_modules/@deepseek-ai/dsh/bin/dsh /usr/local/bin/dsh 2>/dev/null || true

# Android compatibility patch: hard links are denied on app-data filesystems.
COPY runtime-bundle/scripts/patch_dsh_android.js /opt/dshapp/patch_dsh_android.js
RUN node /opt/dshapp/patch_dsh_android.js

# Start script used by the Android Sandbox Manager.
COPY runtime-bundle/scripts/start_dsh.sh /opt/dshapp/start_dsh.sh
COPY runtime-bundle/scripts/healthcheck.sh /opt/dshapp/healthcheck.sh
RUN chmod +x /opt/dshapp/start_dsh.sh /opt/dshapp/healthcheck.sh

WORKDIR /root/projects

ENTRYPOINT ["/bin/bash"]

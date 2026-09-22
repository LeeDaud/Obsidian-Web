FROM node:22-bookworm-slim@sha256:48e4b67d85f87bd551df43704e24d252f56cc5f8e9718841aace50f19948f0f9
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates util-linux \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --legacy-peer-deps --ignore-scripts --no-audit --no-fund
COPY upstream/ignis/package.json upstream/ignis/package-lock.json ./upstream/ignis/
COPY upstream/ignis/packages/server-core ./upstream/ignis/packages/server-core
COPY upstream/ignis/apps/ignis-server ./upstream/ignis/apps/ignis-server
RUN cd upstream/ignis && npm ci --omit=dev --ignore-scripts --no-audit --no-fund \
    --workspace=@ignis/server-core --include-workspace-root
COPY upstream/ignis/packages/shim/dist ./upstream/ignis/packages/shim/dist
COPY upstream/ignis/packages/ui/dist ./upstream/ignis/packages/ui/dist
COPY upstream/ignis/images ./upstream/ignis/images
COPY upstream/ignis/LICENSE ./upstream/ignis/LICENSE
COPY src ./src
COPY deploy/entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh
USER node
EXPOSE 4319
ENTRYPOINT ["/entrypoint.sh"]

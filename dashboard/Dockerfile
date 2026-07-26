# syntax=docker/dockerfile:1
FROM node:22.23.1-bookworm-slim@sha256:6c74791e557ce11fc957704f6d4fe134a7bc8d6f5ca4403205b2966bd488f6b3 AS build

WORKDIR /opt/p2a/dashboard

COPY dashboard/package.json dashboard/pnpm-lock.yaml dashboard/pnpm-workspace.yaml dashboard/.npmrc ./

RUN corepack enable \
    && pnpm install --frozen-lockfile

COPY dashboard/ ./

RUN pnpm run build \
    && test -f dist/index.html \
    && test -f build/server/bootstrap.js \
    && pnpm prune --prod

FROM node:22.23.1-bookworm-slim@sha256:6c74791e557ce11fc957704f6d4fe134a7bc8d6f5ca4403205b2966bd488f6b3 AS runtime

ENV NODE_ENV=production

WORKDIR /opt/p2a/dashboard

COPY --from=build --chown=node:node /opt/p2a/dashboard/package.json ./package.json
COPY --from=build --chown=node:node /opt/p2a/dashboard/node_modules ./node_modules
COPY --from=build --chown=node:node /opt/p2a/dashboard/dist ./dist
COPY --from=build --chown=node:node /opt/p2a/dashboard/build/server ./server

USER node

EXPOSE 4173

CMD ["node", "server/bootstrap.js"]

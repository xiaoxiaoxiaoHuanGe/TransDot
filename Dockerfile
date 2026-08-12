ARG OFFICIAL_IMAGE_REGISTRY=public.ecr.aws/docker/library

FROM ${OFFICIAL_IMAGE_REGISTRY}/node:24-alpine AS web-builder
WORKDIR /src/web

COPY web/package.json web/package-lock.json ./
RUN npm ci

COPY web/ ./
RUN npm run build


FROM ${OFFICIAL_IMAGE_REGISTRY}/golang:1.26-alpine AS server-builder
WORKDIR /src/server

COPY server/go.mod server/go.sum ./
RUN go mod download

COPY server/ ./
COPY --from=web-builder /src/web/dist ./internal/webui/dist

RUN go test ./...
RUN CGO_ENABLED=0 GOOS=linux go build \
    -trimpath \
    -ldflags="-s -w" \
    -o /out/transfer-assistant \
    ./cmd/transfer-assistant


FROM ${OFFICIAL_IMAGE_REGISTRY}/alpine:3.22 AS runtime

RUN apk add --no-cache ca-certificates tzdata \
    && addgroup -S app \
    && adduser -S -D -H -u 10001 -G app app \
    && mkdir -p /app/data/database /app/data/files /app/data/thumbs /app/data/tmp \
    && chown -R app:app /app

WORKDIR /app
COPY --from=server-builder --chown=app:app /out/transfer-assistant /app/transfer-assistant

ENV PORT=5757 \
    DATA_DIR=/app/data

USER app
EXPOSE 5757
VOLUME ["/app/data"]

ENTRYPOINT ["/app/transfer-assistant"]

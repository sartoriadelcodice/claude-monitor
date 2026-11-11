# Build (Angular 22 richiede Node 24.15 o successivo).
# Il contesto è la radice del repository: servono anche gli script di hooks/.
FROM node:24-alpine AS build
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend ./
COPY hooks ../hooks
# Chiave di licenza PrimeUI: finisce nel bundle del browser, non è nel repository
ARG PRIMENG_LICENSE=""
RUN PRIMENG_LICENSE="$PRIMENG_LICENSE" npm run build

# Runtime
FROM nginx:1.29-alpine
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/frontend/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80

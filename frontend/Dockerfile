# Build (Angular 22 requires Node 24.15 or later).
# The context is the repository root: the scripts in hooks/ are needed too.
FROM node:24-alpine AS build
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend ./
COPY hooks ../hooks
# PrimeUI license key: it ends up in the browser bundle, it is not in the repository
ARG PRIMENG_LICENSE=""
RUN PRIMENG_LICENSE="$PRIMENG_LICENSE" npm run build

# Runtime
FROM nginx:1.29-alpine
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/frontend/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80

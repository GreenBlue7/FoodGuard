# ============================================
# Stage 1: Build (Node + npm)
# ============================================
FROM node:24-slim AS builder

WORKDIR /app

# Cache node_modules layer
# Manifests change less often than sources, so the install layer stays cached on code-only edits
COPY package.json package-lock.json ./

# npm ci installs exactly what the lockfile pins, including the devDependencies the build needs
# (vite, vue-tsc); setting NODE_ENV=production here would skip them and break the build
# Cache mount: keeps downloaded packages between builds without storing them in the image
RUN --mount=type=cache,target=/root/.npm npm ci

# .dockerignore excludes the host node_modules, whose macOS binaries would break the Linux build
COPY . .
RUN npm run build

# ============================================
# Stage 2: Runtime (nginx serves static files)
# ============================================
FROM nginx:alpine

# nginx config for SPA routing (Vue Router uses HTML5 history mode)
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy ONLY the built static files
COPY --from=builder /app/dist /usr/share/nginx/html

# HTTP default port
EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]


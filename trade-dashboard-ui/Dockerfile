FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# URLs the browser uses to reach the back-end services.
ARG VITE_QUERY_URL=http://localhost:8083
ARG VITE_SIMULATOR_URL=http://localhost:8084
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80

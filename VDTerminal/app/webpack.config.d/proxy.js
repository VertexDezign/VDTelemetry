// Dev-server proxy: forward WebSocket + API calls to the Ktor server on :3001,
// mirroring the old Vite proxy so the wasm app can be served from the dev server.
config.devServer = config.devServer || {};

// Serve on 5173 (like Vite) and expose it beyond loopback so a LAN device / HTTPS reverse
// proxy can reach it. allowedHosts "all" is required for the proxied custom hostname, which
// webpack-dev-server would otherwise reject with an "Invalid Host header".
config.devServer.port = 5173;
config.devServer.host = "0.0.0.0";
config.devServer.allowedHosts = "all";

config.devServer.proxy = [
    { context: ["/ws"], target: "ws://localhost:3001", ws: true },
    { context: ["/api"], target: "http://localhost:3001" },
];

// webpack-dev-server's own HMR websocket defaults to "/ws", which collides with the app's
// telemetry socket. Move HMR onto a dedicated path so "/ws" is free to proxy to Ktor.
config.devServer.webSocketServer = { type: "ws", options: { path: "/__hmr" } };
// Derive the HMR socket's protocol/host/port from the page location rather than hardcoding the
// dev-server port: "auto" picks ws/wss from the page, "0.0.0.0" reuses the page hostname, and port
// "0" reuses the page port. So direct access uses ws://localhost:5173/__hmr, while the HTTPS reverse
// proxy uses wss://<domain>/__hmr (port 443) instead of a broken wss://<domain>:5173.
config.devServer.client = Object.assign({}, config.devServer.client, {
    webSocketURL: "auto://0.0.0.0:0/__hmr",
});

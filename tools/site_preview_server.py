"""官网本地预览服务器（预发布检查用，非交付物）。

Windows 内置 http.server 会把 .css 返回成 application/x-css（无效 MIME），
浏览器据此拒绝整份样式表 → 页面看起来"没样式"，容易被误判成站点 bug。
这里显式声明 MIME，确保本地预览与 GitHub Pages 行为一致。
"""
import http.server
import os
import socketserver

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "website")


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".css": "text/css",
        ".js": "text/javascript",
        ".html": "text/html",
        ".svg": "image/svg+xml",
        ".json": "application/json",
        ".webmanifest": "application/manifest+json",
    }

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=os.path.abspath(ROOT), **kwargs)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("127.0.0.1", 8899), Handler) as httpd:
        httpd.serve_forever()

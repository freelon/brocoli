from os import curdir
from os.path import join as pjoin
import uuid
import time
import datetime

from http.server import BaseHTTPRequestHandler, HTTPServer

class StoreHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        store_path = pjoin(curdir, str(uuid.uuid4()) + '.upload')
        if self.path == '/upload':
            length = self.headers['content-length']
            data = self.rfile.read(int(length))

            with open(store_path, 'w') as fh:
                fh.write(data.decode())

            self.send_response(200)
            self.send_header('Content-type', 'text/json')
            self.end_headers()
            self.wfile.write('{\"status\": 200}'.encode())
        elif self.path.startswith('/uploadName/'):
            ts = time.time()
            st = datetime.datetime.fromtimestamp(ts).strftime('%Y-%m-%d.%H:%M:%S')
            name = self.path[12:]
            store_path = pjoin(curdir, name + '.' + st + '.upload')
            length = self.headers['content-length']
            data = self.rfile.read(int(length))

            with open(store_path, 'w') as fh:
                fh.write(data.decode())

            self.send_response(200)
            self.send_header('Content-type', 'text/json')
            self.end_headers()
            self.wfile.write('{\"status\": 200}'.encode())


server = HTTPServer(('', 8080), StoreHandler)
server.serve_forever()

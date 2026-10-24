const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PORT = 8080;
const DIST_INDEX = path.join(__dirname, '../dist/index.html');

if (!fs.existsSync(DIST_INDEX)) {
  console.error('Error: dist/index.html not found. Run "npm run build" first.');
  process.exit(1);
}

function getLocalIPs() {
  const interfaces = os.networkInterfaces();
  const addresses = [];
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        addresses.push(iface.address);
      }
    }
  }
  return addresses;
}

const server = http.createServer((req, res) => {
  console.log(`[${new Date().toLocaleTimeString()}] ${req.method} ${req.url}`);

  if (req.url === '/download') {
    res.writeHead(200, {
      'Content-Type': 'application/octet-stream',
      'Content-Disposition': 'attachment; filename="fastread.html"'
    });
    fs.createReadStream(DIST_INDEX).pipe(res);
    return;
  }

  // Serve dist/index.html for any path
  res.writeHead(200, {
    'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': 'no-cache'
  });
  fs.createReadStream(DIST_INDEX).pipe(res);
});

server.listen(PORT, '0.0.0.0', () => {
  const ips = getLocalIPs();
  console.log('====================================================');
  console.log('FastRead Local Server is RUNNING!');
  console.log(`On your computer:  http://localhost:${PORT}`);
  console.log('----------------------------------------------------');
  console.log('On your BOOX Livingstone (open Browser and enter):');
  ips.forEach(ip => {
    console.log(`   👉  http://${ip}:${PORT}`);
  });
  console.log('====================================================');
});

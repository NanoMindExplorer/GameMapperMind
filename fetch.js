import https from 'https';
https.get('https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/README.md', (res) => {
  let data = '';
  res.on('data', chunk => data += chunk);
  res.on('end', () => console.log(data));
}).on('error', console.error);

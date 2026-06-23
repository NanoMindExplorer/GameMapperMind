const fs = require('fs');
let code = fs.readFileSync('src/components/ShizukuPanel.tsx', 'utf-8');
const counts = (code.match(/<div/g) || []).length;
const counts2 = (code.match(/<\/div>/g) || []).length;
console.log('<div:', counts);
console.log('</div:', counts2);
console.log('Missing closing tags:', counts - counts2);

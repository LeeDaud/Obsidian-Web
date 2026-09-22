// Keep this nested project from inheriting the capture app's TypeScript-only test glob.
module.exports = { test: { include: ['packages/**/*.test.{js,mjs}', 'apps/**/*.test.{js,mjs}'] } };

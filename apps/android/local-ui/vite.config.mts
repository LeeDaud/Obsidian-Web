import { defineConfig } from "../../../upstream/memos/web/node_modules/vite/dist/node/index.js";
import react from "../../../upstream/memos/web/node_modules/@vitejs/plugin-react/dist/index.js";
import tailwindcss from "../../../upstream/memos/web/node_modules/@tailwindcss/vite/dist/index.mjs";
import { resolve, dirname } from "node:path";
import { nativeAdapters } from "./adapters.mts";
import { readdirSync, writeFileSync, existsSync, readFileSync } from "node:fs";

const upstream = resolve(import.meta.dirname, "../../../upstream/memos/web");
const local = resolve(import.meta.dirname, "src");
export default defineConfig({
  root: import.meta.dirname,
  publicDir: resolve(upstream, "public"),
  plugins: [nativeAdapters(), react(), tailwindcss(), {
    name: "echo-current-asset-list",
    writeBundle(_options, bundle) {
      const packages = new Map<string, string>();
      for (const chunk of Object.values(bundle)) {
        if (chunk.type !== "chunk") continue;
        for (const path of Object.keys(chunk.modules)) {
          if (!path.includes("node_modules")) continue;
          let directory = dirname(path.split('?')[0]);
          for (let depth = 0; depth < 12; depth++) {
            const manifest = resolve(directory, "package.json");
            if (existsSync(manifest)) {
              const pkg = JSON.parse(readFileSync(manifest, "utf8"));
              if (pkg.name) {
                let license = "";
                for (const filename of ["LICENSE", "LICENSE.md", "LICENSE.txt", "license", "LICENCE", "COPYING"]) {
                  const file = resolve(directory, filename); if (existsSync(file)) { license = readFileSync(file, "utf8"); break; }
                }
                packages.set(`${pkg.name}@${pkg.version}`, `\n${pkg.name} ${pkg.version}\nLicense: ${pkg.license ?? "See upstream package"}\n${license}\n`);
                break;
              }
            }
            const parent = dirname(directory); if (parent === directory) break; directory = parent;
          }
        }
      }
      writeFileSync(resolve(import.meta.dirname, "../app/build/generated/assets/local-ui/THIRD-PARTY-NOTICES.txt"), [...packages.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([, text]) => text).join("\n"));
      const publicFiles: string[] = [];
      const collect = (directory: string, prefix = "") => {
        for (const entry of readdirSync(directory, { withFileTypes: true })) {
          if (entry.isDirectory()) collect(resolve(directory, entry.name), `${prefix}${entry.name}/`);
          else publicFiles.push(`${prefix}${entry.name}`);
        }
      };
      collect(resolve(upstream, "public"));
      writeFileSync(resolve(import.meta.dirname, "../app/build/current-local-ui-assets.json"), JSON.stringify([...new Set([...Object.keys(bundle), ...publicFiles, "THIRD-PARTY-NOTICES.txt"])].sort()));
    },
  }],
  resolve: {
    alias: [
      { find: /^react$/, replacement: resolve(upstream, "node_modules/react") },
      { find: /^react\//, replacement: `${upstream}/node_modules/react/` },
      { find: /^lucide-react$/, replacement: resolve(upstream, "node_modules/lucide-react") },
      { find: /^react-hot-toast$/, replacement: resolve(upstream, "node_modules/react-hot-toast") },
      { find: /^@tanstack\/react-query$/, replacement: resolve(upstream, "node_modules/@tanstack/react-query") },
      { find: /^@\/connect$/, replacement: resolve(local, "connect.ts") },
      { find: /^@\/auth-state$/, replacement: resolve(local, "auth-state.ts") },
      { find: /^@\/hooks\/useLiveMemoRefresh$/, replacement: resolve(local, "live-refresh.ts") },
      { find: /^@\/components\/Settings\/MyAccountSection$/, replacement: resolve(local, "NativeAccountSection.tsx") },
      { find: /^@\/components\/Settings\/settingSections$/, replacement: resolve(local, "setting-sections.ts") },
      { find: /^@\/lib\/browser$/, replacement: resolve(local, "browser.ts") },
      { find: /^@\/lib\/memo-export$/, replacement: resolve(local, "memo-export.ts") },
      { find: /^@\//, replacement: `${upstream}/src/` },
    ],
  },
  build: {
    target: "chrome111",
    outDir: resolve(import.meta.dirname, "../app/build/generated/assets/local-ui"),
    emptyOutDir: false,
    sourcemap: false,
  },
});

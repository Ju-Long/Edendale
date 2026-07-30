import { defineConfig } from "astro/config";

import { DEFAULT_LOCALE, LOCALE_PATHS } from "./src/i18n/locales.ts";

const repository = process.env.GITHUB_REPOSITORY ?? "Ju-Long/Edendale";
const [owner, repositoryName] = repository.split("/");
const customDomain =
  process.env.EDENDALE_CUSTOM_DOMAIN ?? "edendale.babasama.com";
const usesCustomDomain = customDomain.length > 0;

export default defineConfig({
  output: "static",
  site: usesCustomDomain
    ? `https://${customDomain}`
    : `https://${owner.toLowerCase()}.github.io`,
  base: usesCustomDomain ? "/" : `/${repositoryName}`,
  trailingSlash: "always",
  i18n: {
    defaultLocale: DEFAULT_LOCALE,
    locales: LOCALE_PATHS,
    routing: {
      // English keeps the unprefixed URLs and every other language is served
      // from `/<locale>/`, the way apple.com serves `/` and `/jp/`. This also
      // keeps `/search`, `/media/*`, `/library/*`, and `/play/*` exactly where
      // the Apple app site association file claims them.
      prefixDefaultLocale: false,
      redirectToDefaultLocale: false,
    },
  },
});

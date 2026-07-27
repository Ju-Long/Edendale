import { defineConfig } from "astro/config";

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
});

import { defineCollection } from "astro:content";
import { glob } from "astro/loaders";
import { z } from "astro/zod";

/**
 * The privacy policy, one Markdown file per language, named after the locale's
 * URL segment (`en.md`, `pt-br.md`, `zh-hans.md`, …). Short page chrome such as
 * the heading and labels lives in `src/i18n/ui.ts`; the policy text lives here
 * because it is long-form prose with lists and links.
 *
 * The English file is authoritative. `src/pages/[...locale]/privacy.astro`
 * fails the build when a language's file is missing or its `updated` date
 * differs from the English one, so a policy change cannot ship half-translated.
 */
const privacy = defineCollection({
  loader: glob({ pattern: "*.md", base: "./src/content/privacy" }),
  schema: z.object({
    /** The date this version takes effect. Identical in every language. */
    updated: z.coerce.date(),
    /** The key points, shown above the full text. */
    summary: z.array(z.string()).min(3).max(6),
  }),
});

export const collections = { privacy };

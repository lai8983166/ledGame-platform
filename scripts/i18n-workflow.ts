import { readFile, readdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  PLATFORM_LOCALES,
  applyQueuedTranslations,
  catalogInterpolationErrors,
  createTranslationQueue,
  missingTranslationKeys,
  type AuthoredCatalogs,
  type PlatformLocale,
  type TranslationQueueTargets,
} from "../packages/shared-ui/src/index";
import {
  memberAdminAuthoredCatalogs,
  memberAdminBaseCatalog,
} from "../apps/member-admin/src/localization";
import {
  registrationKioskAuthoredCatalogs,
  registrationKioskBaseCatalog,
} from "../apps/registration-kiosk/src/localization";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const translationsPath = path.join(repositoryRoot, "i18n", "translations.json");
const queuePath = path.join(repositoryRoot, "i18n", "translation-queue.json");
const baselinePath = path.join(repositoryRoot, "i18n", "unmarked-baseline.json");

type FlatCatalog = Record<string, string>;
type ApplicationName = "memberAdmin" | "registrationKiosk";
type PersistedFile = {
  schemaVersion: 1;
  sourceLocale: "en-US";
  applications: Record<ApplicationName, { targets: TranslationQueueTargets }>;
};

const applications: Array<{
  name: ApplicationName;
  sourceDirectory: string;
  base: FlatCatalog;
  authored: AuthoredCatalogs<FlatCatalog>;
  criticalKeys: readonly string[];
}> = [
  {
    name: "memberAdmin",
    sourceDirectory: path.join(repositoryRoot, "apps", "member-admin", "src"),
    base: memberAdminBaseCatalog,
    authored: memberAdminAuthoredCatalogs,
    criticalKeys: [
      "language", "chooseLanguage", "navWristbands", "navOverview", "navRooms",
      "navMembers", "navRecords", "navRanking", "navSettings",
    ],
  },
  {
    name: "registrationKiosk",
    sourceDirectory: path.join(repositoryRoot, "apps", "registration-kiosk", "src"),
    base: registrationKioskBaseCatalog,
    authored: registrationKioskAuthoredCatalogs,
    criticalKeys: [
      "language", "chooseLanguage", "titleHome", "welcome", "headlineBefore",
      "headlineAccent", "headlineAfter", "intro", "activateWristband", "playerInfo",
    ],
  },
];

const emptyPersistedFile = (): PersistedFile => ({
  schemaVersion: 1,
  sourceLocale: "en-US",
  applications: {
    memberAdmin: { targets: {} },
    registrationKiosk: { targets: {} },
  },
});

async function readJson<T>(filePath: string, fallback: T): Promise<T> {
  try {
    return JSON.parse(await readFile(filePath, "utf8")) as T;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return fallback;
    throw error;
  }
}

async function sourceFiles(directory: string): Promise<string[]> {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = await Promise.all(entries.map((entry) => {
    const entryPath = path.join(directory, entry.name);
    return entry.isDirectory() ? sourceFiles(entryPath) : [entryPath];
  }));
  return files.flat().filter((file) => /\.(?:vue|ts|js)$/.test(file)).sort();
}

async function extractReferencedKeys(directory: string, knownKeys: readonly string[]): Promise<string[]> {
  const keys = new Set<string>();
  const known = new Set(knownKeys);
  for (const file of await sourceFiles(directory)) {
    const source = await readFile(file, "utf8");
    for (const match of source.matchAll(/\b(?:text|t)\(\s*["'`]([\w.-]+)["'`]/g)) keys.add(match[1]);
    for (const match of source.matchAll(/["'`]([\w.-]+)["'`]/g)) {
      if (known.has(match[1])) keys.add(match[1]);
    }
  }
  return [...keys].sort();
}

function normalizeLiteral(value: string): string {
  return value.replace(/\s+/g, " ").trim();
}

async function unmarkedCopy(directory: string): Promise<string[]> {
  const findings: string[] = [];
  for (const file of (await sourceFiles(directory)).filter((item) => item.endsWith(".vue"))) {
    const source = await readFile(file, "utf8");
    const template = source.match(/<template>([\s\S]*?)<\/template>/)?.[1] || "";
    const relative = path.relative(repositoryRoot, file).replaceAll("\\", "/");
    const literals = [
      ...[...template.matchAll(/>\s*([^<>{}\n][^<>{}]*)\s*</g)].map((match) => match[1]),
      ...[...template.matchAll(/\b(?:placeholder|title|aria-label)=["']([^"']+)["']/g)].map((match) => match[1]),
    ];
    for (const literal of literals.map(normalizeLiteral)) {
      if (!literal || !/[\p{L}\p{Script=Han}]/u.test(literal)) continue;
      if (/^(?:true|false|button|dialog|option|listbox|status)$/i.test(literal)) continue;
      findings.push(`${relative}::${literal}`);
    }
  }
  return [...new Set(findings)].sort();
}

function queueWithPreservedDrafts(
  generated: TranslationQueueTargets,
  previous: TranslationQueueTargets,
): TranslationQueueTargets {
  for (const [locale, entries] of Object.entries(generated) as Array<[PlatformLocale, Record<string, { source: string; translation: string }>]>) {
    for (const [key, entry] of Object.entries(entries)) {
      const previousEntry = previous[locale]?.[key];
      if (previousEntry?.source === entry.source && previousEntry.translation) {
        entry.translation = previousEntry.translation;
      }
    }
  }
  return generated;
}

async function buildState() {
  const persisted = await readJson<PersistedFile>(translationsPath, emptyPersistedFile());
  const previousQueue = await readJson<PersistedFile>(queuePath, emptyPersistedFile());
  const state = [];
  for (const application of applications) {
    const storedTargets = persisted.applications[application.name]?.targets || {};
    const effectiveAuthored = applyQueuedTranslations(application.base, application.authored, storedTargets);
    const referencedKeys = await extractReferencedKeys(application.sourceDirectory, Object.keys(application.base));
    const unknownKeys = referencedKeys.filter((key) => !(key in application.base));
    const missingByLocale = Object.fromEntries(PLATFORM_LOCALES.map(({ code }) => [
      code,
      missingTranslationKeys(application.base, effectiveAuthored, code),
    ])) as Record<PlatformLocale, string[]>;
    const generatedQueue = createTranslationQueue(application.base, effectiveAuthored).targets;
    state.push({
      ...application,
      effectiveAuthored,
      referencedKeys,
      unknownKeys,
      missingByLocale,
      interpolationErrors: catalogInterpolationErrors(application.base, effectiveAuthored),
      queue: queueWithPreservedDrafts(
        generatedQueue,
        previousQueue.applications[application.name]?.targets || {},
      ),
      unmarked: await unmarkedCopy(application.sourceDirectory),
    });
  }
  return { persisted, state };
}

function printReport(state: Awaited<ReturnType<typeof buildState>>["state"]): void {
  for (const application of state) {
    console.log(`\n${application.name}: ${Object.keys(application.base).length} base keys, ${application.referencedKeys.length} referenced keys`);
    for (const { code, flag, label } of PLATFORM_LOCALES) {
      console.log(`  ${flag} ${label} (${code}): ${application.missingByLocale[code].length} missing`);
    }
    console.log(`  unmarked Vue literals: ${application.unmarked.length}`);
  }
}

async function writeQueue(state: Awaited<ReturnType<typeof buildState>>["state"]): Promise<void> {
  const output = emptyPersistedFile();
  for (const application of state) output.applications[application.name].targets = application.queue;
  await writeFile(queuePath, `${JSON.stringify(output, null, 2)}\n`, "utf8");
  console.log(`Wrote incremental translation queue: ${path.relative(repositoryRoot, queuePath)}`);
}

async function importCompletedQueue(): Promise<void> {
  const persisted = await readJson<PersistedFile>(translationsPath, emptyPersistedFile());
  const queue = await readJson<PersistedFile>(queuePath, emptyPersistedFile());
  let imported = 0;
  for (const application of applications) {
    const target = persisted.applications[application.name].targets;
    for (const [locale, entries] of Object.entries(queue.applications[application.name].targets) as Array<[PlatformLocale, Record<string, { source: string; translation: string }>]>) {
      for (const [key, entry] of Object.entries(entries)) {
        if (!entry.translation.trim()) continue;
        target[locale] ||= {};
        target[locale]![key] = { source: entry.source, translation: entry.translation.trim() };
        imported += 1;
      }
    }
  }
  await writeFile(translationsPath, `${JSON.stringify(persisted, null, 2)}\n`, "utf8");
  console.log(`Imported ${imported} completed translations into ${path.relative(repositoryRoot, translationsPath)}`);
}

async function check(state: Awaited<ReturnType<typeof buildState>>["state"]): Promise<void> {
  const errors: string[] = [];
  const baseline = await readJson<Record<ApplicationName, string[]>>(baselinePath, { memberAdmin: [], registrationKiosk: [] });
  if (PLATFORM_LOCALES.some((locale) => !locale.flagCode || !locale.label)) errors.push("Every locale must define a bundled flag code and native label");
  if (new Set(PLATFORM_LOCALES.map((locale) => locale.flagCode)).size !== PLATFORM_LOCALES.length) errors.push("Every locale must define a unique country flag code");
  for (const application of state) {
    errors.push(...application.unknownKeys.map((key) => `${application.name}: unknown source key ${key}`));
    errors.push(...application.interpolationErrors.map((error) => `${application.name}: ${error}`));
    for (const locale of PLATFORM_LOCALES.map((item) => item.code).filter((item) => item !== "en-US")) {
      for (const key of application.criticalKeys) {
        if (application.missingByLocale[locale].includes(key)) errors.push(`${application.name}: ${locale} missing critical key ${key}`);
      }
    }
    const allowed = new Set(baseline[application.name] || []);
    errors.push(...application.unmarked.filter((item) => !allowed.has(item)).map((item) => `${application.name}: new unmarked copy ${item}`));
  }
  if (errors.length) throw new Error(`Localization check failed:\n${errors.map((error) => `- ${error}`).join("\n")}`);
  console.log("Localization check passed: flags, keys, interpolation, critical coverage, and unmarked-copy baseline.");
}

async function main(): Promise<void> {
  const command = process.argv[2] || "report";
  if (command === "import") await importCompletedQueue();
  const { state } = await buildState();
  if (command === "report") return printReport(state);
  if (command === "extract" || command === "import") {
    await writeQueue(state);
    return printReport(state);
  }
  if (command === "baseline") {
    const output = Object.fromEntries(state.map((application) => [application.name, application.unmarked]));
    await writeFile(baselinePath, `${JSON.stringify(output, null, 2)}\n`, "utf8");
    return console.log(`Wrote unmarked-copy baseline: ${path.relative(repositoryRoot, baselinePath)}`);
  }
  if (command === "check") return check(state);
  throw new Error(`Unknown command: ${command}`);
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
});

#!/usr/bin/env node

const fs = require("fs");

function readArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith("--")) {
      throw new Error(`Unexpected argument: ${arg}`);
    }
    const key = arg.slice(2);
    const value = argv[i + 1];
    if (!value || value.startsWith("--")) {
      throw new Error(`Missing value for --${key}`);
    }
    args[key] = value;
    i += 1;
  }
  return args;
}

function requireArg(args, key) {
  const value = args[key];
  if (!value) {
    throw new Error(`Required argument missing: --${key}`);
  }
  return value;
}

function formatNotes(changes) {
  if (!Array.isArray(changes) || changes.length === 0) {
    throw new Error("Changelog entry must contain a non-empty changes array");
  }
  return changes.map((change) => `- ${String(change).trim()}`).join("\n");
}

function main() {
  const args = readArgs(process.argv.slice(2));
  const channel = requireArg(args, "channel");
  const version = requireArg(args, "version");
  const apkUrl = requireArg(args, "apk-url");
  const output = requireArg(args, "output");
  const changelogPath = args.changelog || "changelog.json";

  const changelog = JSON.parse(fs.readFileSync(changelogPath, "utf8"));
  const entries = changelog[channel];
  if (!Array.isArray(entries)) {
    throw new Error(`Changelog channel not found: ${channel}`);
  }

  const entry = entries.find((candidate) => candidate.version === version);
  if (!entry) {
    throw new Error(`No changelog entry for ${channel} ${version} in ${changelogPath}`);
  }

  const versionCodeRaw = args["version-code"] || entry.versionCode;
  const versionCode = Number(versionCodeRaw);
  if (!Number.isInteger(versionCode) || versionCode <= 0) {
    throw new Error(`Invalid --version-code: ${versionCodeRaw}`);
  }
  if (entry.versionCode !== undefined && Number(entry.versionCode) !== versionCode) {
    throw new Error(
      `Version code mismatch for ${channel} ${version}: changelog=${entry.versionCode}, manifest=${versionCode}`
    );
  }

  const notes = formatNotes(entry.changes);
  const manifest = {
    version,
    versionName: version,
    versionCode,
    url: apkUrl,
    apkUrl,
    notes,
    changelog: notes,
    channel,
    date: entry.date || null
  };

  fs.writeFileSync(output, `${JSON.stringify(manifest)}\n`);
}

try {
  main();
} catch (error) {
  console.error(error.message);
  process.exit(1);
}

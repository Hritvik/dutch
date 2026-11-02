import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const root = path.resolve("apps/mobile/app");
const issues = [];
let inspected = 0;

const REQUIREMENTS = {
  Pressable: "accessibilityRole",
  Image: "accessibilityLabel",
  TextInput: "accessibilityLabel",
  Switch: "accessibilityLabel"
};

function walk(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      walk(full);
    } else if (entry.name.endsWith(".tsx")) {
      inspected += 1;
      inspect(full);
    }
  }
}

/** Finds the end of a JSX opening tag while skipping braces and quoted strings. */
function openingTag(source, start) {
  let braces = 0;
  let quote = null;
  for (let index = start; index < source.length; index += 1) {
    const char = source[index];
    if (quote) {
      if (char === quote && source[index - 1] !== "\\") quote = null;
      continue;
    }
    if (char === '"' || char === "'" || char === "`") {
      quote = char;
    } else if (char === "{") {
      braces += 1;
    } else if (char === "}") {
      braces -= 1;
    } else if (char === ">" && braces === 0) {
      return source.slice(start, index + 1);
    }
  }
  return null;
}

function hasNonEmptyAttribute(opening, name) {
  const match = new RegExp(`${name}\\s*=\\s*("([^"]*)"|'([^']*)'|\\{` + '`([^`]*)`' + '\\})').exec(opening);
  if (!match) return false;
  const value = match[2] ?? match[3] ?? match[4] ?? "";
  return value.trim().length > 0;
}

function inspect(file) {
  const source = fs.readFileSync(file, "utf8");
  for (const [tag, attribute] of Object.entries(REQUIREMENTS)) {
    const pattern = new RegExp(`<${tag}\\b`, "g");
    for (const match of source.matchAll(pattern)) {
      const opening = openingTag(source, match.index);
      if (!opening) {
        issues.push(`${file}:${lineOf(source, match.index)} ${tag} tag is not closed`);
        continue;
      }
      if (!hasNonEmptyAttribute(opening, attribute)) {
        issues.push(`${file}:${lineOf(source, match.index)} ${tag} lacks ${attribute}`);
      }
    }
  }
}

function lineOf(source, index) {
  return source.slice(0, index).split("\n").length;
}

if (!fs.existsSync(root)) {
  console.error(`Accessibility scan root is missing: ${root}`);
  process.exitCode = 1;
} else {
  walk(root);
  if (inspected === 0) {
    console.error("Accessibility scan found no .tsx files.");
    process.exitCode = 1;
  }
}
if (process.exitCode !== 1 && issues.length > 0) {
  console.error(issues.join("\n"));
  process.exitCode = 1;
} else if (process.exitCode !== 1) {
  console.log(`Accessibility static checks passed (${inspected} screens).`);
}

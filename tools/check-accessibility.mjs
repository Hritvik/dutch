import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const root = path.resolve("apps/mobile/app");
const issues = [];

function walk(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      walk(full);
    } else if (entry.name.endsWith(".tsx")) {
      inspect(full);
    }
  }
}

function inspect(file) {
  const source = fs.readFileSync(file, "utf8");
  for (const match of source.matchAll(/<Pressable\b/g)) {
    const end = source.indexOf(">", match.index);
    const opening = source.slice(match.index, end === -1 ? undefined : end + 1);
    if (!opening.includes("accessibilityRole")) {
      issues.push(`${file}:${lineOf(source, match.index)} Pressable lacks accessibilityRole`);
    }
  }
  for (const match of source.matchAll(/<Image\b/g)) {
    const end = source.indexOf(">", match.index);
    const opening = source.slice(match.index, end === -1 ? undefined : end + 1);
    if (!opening.includes("accessibilityLabel")) {
      issues.push(`${file}:${lineOf(source, match.index)} Image lacks accessibilityLabel`);
    }
  }
}

function lineOf(source, index) {
  return source.slice(0, index).split("\n").length;
}

if (fs.existsSync(root)) {
  walk(root);
}
if (issues.length > 0) {
  console.error(issues.join("\n"));
  process.exitCode = 1;
} else {
  console.log("Accessibility static checks passed.");
}

import { existsSync, readFileSync, readdirSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const windows = process.platform === "win32";
const properties = readFileSync(join(root, "gradle/wrapper/gradle-wrapper.properties"), "utf8");
const version = properties.match(/gradle-([\d.]+)-bin/)?.[1];
const wrapper = join(root, windows ? "gradlew.bat" : "gradlew");
let gradle = process.env.GRADLE_EXECUTABLE;
if (!gradle && existsSync(wrapper)) gradle = wrapper;
if (!gradle && version) {
  const cache = join(process.env.GRADLE_USER_HOME ?? join(homedir(), ".gradle"), "wrapper/dists", `gradle-${version}-bin`);
  if (existsSync(cache)) for (const folder of readdirSync(cache)) {
    const candidate = join(cache, folder, `gradle-${version}/bin`, windows ? "gradle.bat" : "gradle");
    if (existsSync(candidate)) { gradle = candidate; break; }
  }
}
gradle ??= windows ? "gradle.bat" : "gradle";
const env = {...process.env};
if (!env.JAVA_HOME && windows && existsSync("C:/Program Files/Android/Android Studio/jbr")) env.JAVA_HOME = "C:/Program Files/Android/Android Studio/jbr";
const args = [":shared:jsBrowserProductionLibraryDistribution", ":shared-ui:jsBrowserProductionLibraryDistribution", "--console=plain"];
const result = windows
  ? spawnSync(process.env.ComSpec ?? "cmd.exe", ["/d", "/s", "/c", `""${gradle}" ${args.join(" ")}"`], {cwd: root, env, stdio:"inherit", windowsVerbatimArguments:true})
  : spawnSync(gradle, args, {cwd: root, env, stdio:"inherit"});
if (result.error) console.error("Install Gradle or set GRADLE_EXECUTABLE:", result.error.message);
process.exit(result.status ?? 1);

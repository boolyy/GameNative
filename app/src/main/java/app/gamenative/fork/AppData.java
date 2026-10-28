package app.gamenative.fork;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import app.gamenative.BuildConfig;

/**
 * GameNative Perf (see FORK.md): the app's private data directory, derived from the package
 * actually installed. Upstream hardcodes {@code /data/data/app.gamenative}; the side-by-side
 * build is {@code app.gamenative.perf}, so every such path goes through here.
 */
public final class AppData {
    private AppData() {}

    /** The package upstream hardcodes, kept so its paths can be rewritten (see {@link #fixVulkanIcdPaths}). */
    public static final String UPSTREAM_PACKAGE = "app.gamenative";

    public static final String DATA_DIR = "/data/data/" + BuildConfig.APPLICATION_ID;
    public static final String FILES_DIR = DATA_DIR + "/files";
    public static final String IMAGEFS_DIR = FILES_DIR + "/imagefs";
    public static final String STORAGE_DIR = DATA_DIR + "/storage";
    /** Suffix of the E: drive path, for "is this the app storage drive" checks. */
    public static final String STORAGE_SUFFIX = "/" + BuildConfig.APPLICATION_ID + "/storage";

    /**
     * Vulkan ICD manifests shipped in driver archives (Turnip, and drivers downloaded from
     * GameNative's content server) carry an absolute {@code library_path} under the upstream
     * package's data directory. Rewrites them to this package's directory. A no-op for the
     * upstream package itself and for manifests that are already correct.
     *
     * @return number of files changed
     */
    public static int fixVulkanIcdPaths(File icdDir) {
        if (BuildConfig.APPLICATION_ID.equals(UPSTREAM_PACKAGE)) return 0;
        File[] files = icdDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return 0;
        String from = "/data/data/" + UPSTREAM_PACKAGE + "/";
        String to = DATA_DIR + "/";
        int changed = 0;
        for (File f : files) {
            try {
                String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                if (!text.contains(from)) continue;
                Files.write(f.toPath(), text.replace(from, to).getBytes(StandardCharsets.UTF_8));
                changed++;
            } catch (Exception ignored) {
            }
        }
        return changed;
    }
}

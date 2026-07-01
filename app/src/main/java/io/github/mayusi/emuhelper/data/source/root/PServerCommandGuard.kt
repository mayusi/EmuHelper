package io.github.mayusi.emuhelper.data.source.root

/**
 * THE SAFETY MODEL for the optional PServer root bridge.
 *
 * The PServer vendor binder runs whatever shell string we hand it AS ROOT. There is no sandbox
 * below this layer — so this guard *is* the sandbox. It is a strict, DEFAULT-DENY allow-list:
 * a command is rejected unless it is one of a small set of explicitly-modelled, fully
 * argument-validated, path-validated forms. Anything unrecognised, ambiguous, or even slightly
 * off-pattern is DENIED. A bug that lets an unintended command through is a root exploit, so the
 * design bias throughout is "when in doubt, deny".
 *
 * The model in layers (each layer can only DENY; only the final per-segment matcher can ALLOW):
 *   1. Tripwires — empty / NUL / CR / raw-newline / control bytes -> instant deny.
 *   2. Quote-aware segmentation — split on the shell operators && || ; | with single-quote
 *      content treated as literal (operators inside '...' are NOT separators). Each resulting
 *      segment must independently pass; an empty segment (e.g. `a && && b`) is a deny.
 *   3. Per-segment verb inspection — tokenize the segment, look at verb (argv[0]):
 *        - hard-deny verbs -> deny outright (defence in depth; also caught by "not allow-listed").
 *        - allow-listed verbs -> validate every argument and (for writes) every destination path.
 *        - anything else -> deny (the default-deny backstop).
 *
 * NOTE: this guard is solely about *which command strings* are permitted to reach root. Detection
 * of whether root is available at all lives in PServerBridge and is purely "does a no-op transact
 * succeed" — no system-mode probing of any kind.
 */
object PServerCommandGuard {

    sealed class Verdict {
        /** Command matched an allow-listed, fully-validated form and may be transacted. */
        object Allow : Verdict()
        /** Command rejected. [reason] is a short human string (surfaced as "BLOCKED: <reason>"). */
        data class Deny(val reason: String) : Verdict()
    }

    // ---------------------------------------------------------------------------------------------
    // Allow-listed destination roots for WRITE operations (cp/mv targets, mkdir, chmod, chown).
    // A write whose final canonical target is not under one of these is denied. Reads (cat/ls/
    // stat) are NOT restricted to these — reading is non-destructive.
    // ---------------------------------------------------------------------------------------------

    /** Emulator app package ids whose private data dirs are legitimate ROM/save destinations. */
    private val EMULATOR_PACKAGES: List<String> = listOf(
        "com.retroarch",                 // RetroArch
        "com.retroarch.aarch64",         // RetroArch (64-bit channel)
        "org.dolphinemu.dolphinemu",     // Dolphin
        "org.citra.citra_emu",           // Citra
        "org.citra.citra_emu.canary",
        "io.github.lime3ds.android",     // Lime3DS (Citra fork)
        "org.ppsspp.ppsspp",             // PPSSPP
        "org.ppsspp.ppssppgold",
        "xyz.aethersx2.android",         // AetherSX2
        "ru.nethersx2.android",          // NetherSX2 (AetherSX2 fork)
        "com.flycast.emulator",          // Flycast
        "org.mupen64plusae.v3.alpha",    // Mupen64Plus AE
        "com.fastredmercury.flycast",
        "me.magnum.melonds",             // melonDS
        "skyline.emu",                   // Skyline (Switch)
        "org.yuzu.yuzu_emu",             // yuzu
        "org.ryujinx.android",           // Ryujinx
        "com.github.stenzek.duckstation" // DuckStation
    )

    /**
     * Static, always-allowed write roots (shared storage). Note the trailing slash: matching is
     * strict prefix-on-a-path-boundary so `/sdcardX` can never satisfy `/sdcard/`.
     */
    private val STATIC_WRITE_ROOTS: List<String> = listOf(
        "/sdcard/",
        "/storage/emulated/0/",
        "/storage/self/primary/",
        // App-private dirs for THIS app's RELEASE package — safe to stage into.
        "/data/data/io.github.mayusi.emuhelper/",
        "/data/user/0/io.github.mayusi.emuhelper/"
    )

    /**
     * THIS app's own private dirs under its RUNTIME package id — registered once at startup via
     * [registerOwnPackage]. The runtime id carries an applicationId suffix on non-release builds
     * (e.g. `io.github.mayusi.emuhelper.debug`), so without this the fast-path `cp` whose SOURCE is
     * the cache file would be wrongly denied on every debug install. Only ever holds roots for this
     * app's own package — never widens to an arbitrary package. @Volatile for the init-then-read race.
     */
    @Volatile private var ownPackageRoots: List<String> = emptyList()

    /**
     * Register this app's runtime package so its private dirs are valid staging write roots. Safe to
     * call repeatedly (idempotent for the same package). Pass `context.packageName`. Ignores blank or
     * malformed package ids (must match a conservative package-name shape) so a bad value can never
     * inject an unexpected root.
     */
    fun registerOwnPackage(pkg: String) {
        if (!pkg.matches(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$"))) return
        ownPackageRoots = listOf("/data/data/$pkg/", "/data/user/0/$pkg/")
    }

    /** Full set of allowed write roots = static shared storage + this app's runtime dirs +
     *  every known emulator's data dir. Computed per-access so a late [registerOwnPackage] is seen. */
    private val WRITE_ROOTS: List<String>
        get() = buildList {
            addAll(STATIC_WRITE_ROOTS)
            addAll(ownPackageRoots)
            for (pkg in EMULATOR_PACKAGES) {
                add("/data/data/$pkg/")
                add("/data/user/0/$pkg/")
            }
        }

    /**
     * Absolute, no-exceptions deny prefixes for WRITES. Even if some accident extended the write
     * roots, a target under any of these is rejected. System partitions and kernel/process
     * pseudo-filesystems must never be a write destination.
     */
    private val WRITE_DENY_ROOTS: List<String> = listOf(
        "/system", "/vendor", "/boot", "/proc", "/sys", "/dev",
        "/init", "/odm", "/product", "/apex", "/config", "/cache",
        "/metadata", "/persist", "/efs", "/firmware", "/oem"
    )

    // ---------------------------------------------------------------------------------------------
    // Verb sets.
    // ---------------------------------------------------------------------------------------------

    /**
     * Absolute hard-deny verbs. These can destroy data, reflash, change the running system, or
     * spawn a fresh (unguarded) shell. We ONLY ever use the pserver shell itself; we never spawn
     * su/sh/bash/busybox/toybox from within it. This set is redundant with default-deny but kept
     * explicit as defence-in-depth and to give a clear reason string.
     */
    private val HARD_DENY_VERBS: Set<String> = setOf(
        "rm", "dd", "mkfs", "mke2fs", "format", "mknod", "ln", "unlink", "truncate",
        "shred", "wipe", "fastboot", "reboot", "poweroff", "svc", "content", "setenforce",
        "init", "mount", "umount", "insmod", "rmmod", "modprobe", "busybox", "toybox",
        "sh", "bash", "su", "chroot", "nsenter", "blockdev", "sgdisk", "parted",
        "tune2fs", "resize2fs", "e2fsck", "fsck"
    )

    /**
     * Safe modes for chmod. Read/traverse for emulators, never the setuid/setgid/sticky bits and
     * never group/other-write beyond the conventional 644/664/755/775. 777 is intentionally absent.
     */
    private val SAFE_CHMOD_MODES: Set<String> = setOf(
        "644", "664", "755", "775", "640", "750", "600", "700",
        // Symbolic equivalents we accept (read-friendly, no setuid/setgid/sticky).
        "u+rw", "ugo+r", "a+r", "u+rwx"
    )

    // ---------------------------------------------------------------------------------------------
    // Public entry point.
    // ---------------------------------------------------------------------------------------------

    fun inspect(command: String): Verdict {
        // --- Layer 1: tripwires -----------------------------------------------------------------
        if (command.isEmpty()) return Verdict.Deny("empty command")
        if (command.isBlank()) return Verdict.Deny("blank command")
        if (command.contains('\u0000')) return Verdict.Deny("NUL byte")
        if (command.contains('\r')) return Verdict.Deny("carriage return")
        if (command.contains('\n')) return Verdict.Deny("newline")
        // Any other control character (tabs included) — keep the surface tiny and predictable.
        if (command.any { it.code < 0x20 }) return Verdict.Deny("control character")

        // Reject shell substitution / expansion outright BEFORE segmenting. These never appear in
        // any allowed form and are the classic injection vectors. Backticks and $(...) run
        // sub-commands; bare $ enables variable/arith expansion; redirects write arbitrary files;
        // glob chars enable wildcard matching; ~ is home expansion. (Single-quote literal handling
        // below means a literal '$' inside quotes is fine; this check runs on the raw string, so we
        // only flag these when they could be active. To stay strict, we flag them unconditionally —
        // none of our allowed commands legitimately need them.)
        for (meta in DISALLOWED_METACHARS) {
            if (command.contains(meta)) return Verdict.Deny("metacharacter '$meta'")
        }

        // --- Layer 2: quote-aware segmentation ---------------------------------------------------
        val segments = splitSegments(command)
            ?: return Verdict.Deny("unterminated quote")
        if (segments.isEmpty()) return Verdict.Deny("no command")

        // --- Layer 3: every segment must independently pass --------------------------------------
        for (seg in segments) {
            val trimmed = seg.trim()
            if (trimmed.isEmpty()) return Verdict.Deny("empty segment")
            val verdict = inspectSegment(trimmed)
            if (verdict is Verdict.Deny) return verdict
        }
        return Verdict.Allow
    }

    // ---------------------------------------------------------------------------------------------
    // Segment splitting — quote-aware. Splits on && || ; | . Inside single quotes everything is
    // literal (so `echo 'a && b'` would be ONE segment — though echo is denied anyway). Returns
    // null if a single quote is left unterminated (treated as a hard deny by the caller).
    //
    // Double quotes are deliberately NOT given literal semantics: in a real shell they still allow
    // $-expansion, and we already deny `$`/backtick/`"` via DISALLOWED_METACHARS, so a double quote
    // can never legitimately appear. Keeping only single-quote literal handling matches the proven
    // CalibrateSoc splitter and keeps the parser small and auditable.
    // ---------------------------------------------------------------------------------------------

    private fun splitSegments(command: String): List<String>? {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var inSingle = false
        var i = 0
        while (i < command.length) {
            val c = command[i]
            if (c == '\'') {
                inSingle = !inSingle
                current.append(c)
                i++
                continue
            }
            if (!inSingle) {
                // && and ||
                if ((c == '&' || c == '|') && i + 1 < command.length && command[i + 1] == c) {
                    out.add(current.toString()); current.clear(); i += 2; continue
                }
                // single | (pipe) and ; and a lone & (background) are also separators
                if (c == '|' || c == ';' || c == '&') {
                    out.add(current.toString()); current.clear(); i++; continue
                }
            }
            current.append(c)
            i++
        }
        if (inSingle) return null // unterminated quote
        out.add(current.toString())
        return out
    }

    /** Tokenize a single segment into argv, honouring single-quoted literals (quotes stripped). */
    private fun tokenize(segment: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var inSingle = false
        var hasToken = false
        for (c in segment) {
            when {
                c == '\'' -> { inSingle = !inSingle; hasToken = true }
                inSingle -> { current.append(c); hasToken = true }
                c == ' ' || c == '\t' -> {
                    if (hasToken) { tokens.add(current.toString()); current.clear(); hasToken = false }
                }
                else -> { current.append(c); hasToken = true }
            }
        }
        if (hasToken) tokens.add(current.toString())
        return tokens
    }

    // ---------------------------------------------------------------------------------------------
    // Per-segment verb dispatch.
    // ---------------------------------------------------------------------------------------------

    private fun inspectSegment(segment: String): Verdict {
        val tokens = tokenize(segment)
        if (tokens.isEmpty()) return Verdict.Deny("empty segment")
        val verb = tokens[0]
        val args = tokens.drop(1)

        if (verb in HARD_DENY_VERBS) return Verdict.Deny("forbidden command '$verb'")

        return when (verb) {
            "true", "false" -> if (args.isEmpty()) Verdict.Allow
                               else Verdict.Deny("'$verb' takes no arguments")
            "id" -> if (args.isEmpty()) Verdict.Allow
                    else Verdict.Deny("'id' takes no arguments")
            "getprop" -> inspectGetprop(args)
            "cat" -> inspectRead(args, "cat")
            "ls" -> inspectRead(args, "ls")
            "stat" -> inspectRead(args, "stat")
            "mkdir" -> inspectMkdir(args)
            "chmod" -> inspectChmod(args)
            "chown" -> Verdict.Deny("chown not permitted") // see note below
            "cp" -> inspectCopy(args)
            "mv" -> inspectMove(args)
            else -> Verdict.Deny("command '$verb' not allow-listed")
        }
    }

    // NOTE on chown: the spec allows it only "if needed", and tells us to DENY when unsure. For the
    // foundation we deny chown — making a ROM readable by an emulator is handled with chmod 644/755,
    // and chown to an arbitrary owner is a footgun (wrong uid can brick an app's data dir). Left as
    // an explicit, easy-to-revisit deny rather than a risky guess.

    // ---------------------------------------------------------------------------------------------
    // Verb validators.
    // ---------------------------------------------------------------------------------------------

    private fun inspectGetprop(args: List<String>): Verdict {
        // getprop <key> only; key is a conservative dotted identifier. No bare `getprop` (dumps all
        // props — harmless but we keep the surface explicit) and never a flag.
        if (args.size != 1) return Verdict.Deny("getprop takes exactly one key")
        val key = args[0]
        if (key.startsWith("-")) return Verdict.Deny("getprop flags not allowed")
        if (!key.matches(PROP_KEY_RE)) return Verdict.Deny("invalid getprop key")
        return Verdict.Allow
    }

    /** Read verbs: cat/ls/stat. No flags, exactly one path, path must be a clean absolute path. */
    private fun inspectRead(args: List<String>, verb: String): Verdict {
        if (args.size != 1) return Verdict.Deny("$verb takes exactly one path")
        val path = args[0]
        if (path.startsWith("-")) return Verdict.Deny("$verb flags not allowed")
        val norm = normalizeAbsolute(path) ?: return Verdict.Deny("invalid path for $verb")
        // Reads are permitted anywhere readable (for verification). We still require a clean,
        // traversal-free absolute path so nothing weird reaches root.
        if (norm.isEmpty()) return Verdict.Deny("invalid path for $verb")
        return Verdict.Allow
    }

    private fun inspectMkdir(args: List<String>): Verdict {
        // mkdir -p <dir>  OR  mkdir <dir>. -p is the only allowed flag.
        val (flags, operands) = partitionFlags(args)
        for (f in flags) if (f != "-p") return Verdict.Deny("mkdir flag '$f' not allowed")
        if (operands.size != 1) return Verdict.Deny("mkdir takes exactly one directory")
        val dir = normalizeAbsolute(operands[0]) ?: return Verdict.Deny("invalid mkdir path")
        return requireWriteRoot(dir, "mkdir")
    }

    private fun inspectChmod(args: List<String>): Verdict {
        // chmod <mode> <path>  (no -R: recursive perms changes are too broad for the foundation).
        val (flags, operands) = partitionFlags(args)
        if (flags.isNotEmpty()) return Verdict.Deny("chmod flags not allowed")
        if (operands.size != 2) return Verdict.Deny("chmod takes a mode and one path")
        val mode = operands[0]
        if (mode !in SAFE_CHMOD_MODES) return Verdict.Deny("chmod mode '$mode' not allowed")
        val path = normalizeAbsolute(operands[1]) ?: return Verdict.Deny("invalid chmod path")
        return requireWriteRoot(path, "chmod")
    }

    /**
     * cp [-r] <src> <dst>. Exactly two operands. The destination is the only place we WRITE, so it
     * must be under an allowed write root; the source need only be a clean absolute path (cp READS
     * the source non-destructively — it never deletes it — so reading from e.g. a system path is
     * harmless). -r/-R is permitted for the recursive copy of a ROM set.
     */
    private fun inspectCopy(args: List<String>): Verdict {
        val (flags, operands) = partitionFlags(args)
        for (f in flags) {
            if (f != "-r" && f != "-R") return Verdict.Deny("cp flag '$f' not allowed")
        }
        if (operands.size != 2) return Verdict.Deny("cp takes exactly a source and destination")
        val src = normalizeAbsolute(operands[0]) ?: return Verdict.Deny("invalid cp source")
        val dst = normalizeAbsolute(operands[1]) ?: return Verdict.Deny("invalid cp destination")
        if (src.isEmpty()) return Verdict.Deny("invalid cp source")
        return requireWriteRoot(dst, "cp")
    }

    /**
     * mv <src> <dst>. Exactly two operands, NO flags. THE SECURITY-CRITICAL DIFFERENCE FROM cp:
     * `mv` DELETES the source. A loose rule here would let root delete an arbitrary file
     * (`mv /system/x /sdcard/y` would unlink /system/x). So mv demands MORE than cp:
     *
     *   - NO flags at all (no `-f`, no `-r`, nothing — plain `mv <src> <dst>` only). partitionFlags
     *     treats any leading-'-' token as a flag, so `-f`/`-t dir`/etc. are rejected outright.
     *   - EXACTLY 2 operands (a stray third operand, or `mv -t DIR a b`, is denied).
     *   - BOTH operands clean, absolute, traversal-free paths (normalizeAbsolute).
     *   - BOTH the SOURCE and the DESTINATION must pass requireWriteRoot — i.e. each must sit under
     *     an allowed write root (this app's own private dirs / shared storage / a known emulator dir)
     *     and NOT under any WRITE_DENY_ROOT (system/kernel partitions). Constraining the SOURCE the
     *     same way as the destination is the whole point: mv can then only ever move a file BETWEEN
     *     already-writable locations — places the app could legitimately write to and delete from
     *     anyway — so allowing mv grants no new destructive power. It can NEVER move/delete a system
     *     path (`mv /system/x ...`) or another app's data (`mv /data/data/<otherpkg>/x ...`), because
     *     those fail the source write-root check.
     *
     * Default-deny throughout: if anything is even slightly off-spec, DENY.
     */
    private fun inspectMove(args: List<String>): Verdict {
        val (flags, operands) = partitionFlags(args)
        if (flags.isNotEmpty()) return Verdict.Deny("mv flags not allowed")
        if (operands.size != 2) return Verdict.Deny("mv takes exactly a source and destination")
        val src = normalizeAbsolute(operands[0]) ?: return Verdict.Deny("invalid mv source")
        val dst = normalizeAbsolute(operands[1]) ?: return Verdict.Deny("invalid mv destination")
        if (src.isEmpty()) return Verdict.Deny("invalid mv source")
        if (dst.isEmpty()) return Verdict.Deny("invalid mv destination")
        // SOURCE constraint (mv deletes it): the source must itself be under an allowed write root —
        // never a system or other-app path. Checked BEFORE the destination so a system source is
        // reported as a source violation.
        val srcVerdict = requireWriteRoot(src, "mv source")
        if (srcVerdict is Verdict.Deny) return srcVerdict
        // DESTINATION constraint (where we write the moved file): same write-root requirement.
        return requireWriteRoot(dst, "mv")
    }

    // ---------------------------------------------------------------------------------------------
    // Path + write-root validation.
    // ---------------------------------------------------------------------------------------------

    /**
     * Normalize an absolute path WITHOUT touching the filesystem (the path may not exist yet for a
     * write target, and we must not follow symlinks). Rules:
     *   - must start with '/'.
     *   - reject any '..' component (traversal) — even after collapsing, a literal `..` is refused.
     *   - reject empty components from `//` (ambiguous) by collapsing, and reject '.' components.
     *   - reject any character outside a strict safe set (defence beyond the metachar pass).
     * Returns the cleaned absolute path, or null if the path is unsafe/relative.
     */
    private fun normalizeAbsolute(raw: String): String? {
        if (raw.isEmpty()) return null
        if (!raw.startsWith("/")) return null // must be absolute; no relative paths to root
        // Strict character allow-list for a path: letters, digits, and a small punctuation set.
        // Notably EXCLUDES spaces (each path is a single token already), and all shell metachars.
        if (!raw.matches(PATH_RE)) return null
        val parts = raw.split('/')
        val cleaned = ArrayList<String>()
        for (p in parts) {
            when (p) {
                "" -> { /* from leading slash or a doubled slash — skip */ }
                "." -> return null   // refuse '.' components outright (keep paths explicit)
                ".." -> return null  // TRAVERSAL — hard refuse
                else -> cleaned.add(p)
            }
        }
        return "/" + cleaned.joinToString("/")
    }

    /** Require that a (already-normalized) write target sits under an allowed root and not a denied one. */
    private fun requireWriteRoot(normalizedPath: String, verb: String): Verdict {
        // Belt: re-confirm no traversal slipped through.
        if (normalizedPath.contains("/../") || normalizedPath.endsWith("/..") || normalizedPath == "..") {
            return Verdict.Deny("path traversal in $verb target")
        }
        // Hard deny: system/kernel partitions, regardless of anything else.
        for (deny in WRITE_DENY_ROOTS) {
            if (normalizedPath == deny || normalizedPath.startsWith("$deny/")) {
                return Verdict.Deny("$verb into protected '$deny' denied")
            }
        }
        // Allow only if under a known-good write root.
        for (root in WRITE_ROOTS) {
            // root ends with '/'; require the path to be strictly inside it (boundary-safe).
            if (normalizedPath.startsWith(root) && normalizedPath.length > root.length) {
                return Verdict.Allow
            }
        }
        return Verdict.Deny("$verb destination not under an allowed root")
    }

    /** Split argv into (flags starting with '-', everything else). Order within operands preserved. */
    private fun partitionFlags(args: List<String>): Pair<List<String>, List<String>> {
        val flags = ArrayList<String>()
        val operands = ArrayList<String>()
        for (a in args) {
            if (a.startsWith("-") && a.length > 1) flags.add(a) else operands.add(a)
        }
        return flags to operands
    }

    // ---------------------------------------------------------------------------------------------
    // Constants.
    // ---------------------------------------------------------------------------------------------

    /**
     * Characters that must never appear anywhere in a command. Each enables sub-shells, expansion,
     * redirection, globbing, or home-expansion — none of which any allowed form needs. The space
     * char is intentionally NOT here (commands have spaces between tokens); separation/quoting are
     * handled structurally above.
     */
    private val DISALLOWED_METACHARS: List<Char> = listOf(
        '`', '$', '>', '<', '*', '?', '~', '"', '(', ')', '{', '}', '[', ']', '\\', '!', '#', '%'
    )

    // A getprop key: dotted lowercase/upper identifier segments (e.g. ro.product.model). No wildcards.
    private val PROP_KEY_RE = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*$")

    // A path's permitted characters: must start with '/', then alnum plus a small filename-safe set
    // ( . _ - + @ = , ). NO spaces, NO quotes, NO shell metachars (those are already rejected by the
    // metachar pass; this is defence-in-depth). '..' is allowed as characters here, then refused as a
    // *component* by normalizeAbsolute, which is the real traversal guard.
    private val PATH_RE = Regex("^/[A-Za-z0-9_./+\\-@=,]*$")
}
